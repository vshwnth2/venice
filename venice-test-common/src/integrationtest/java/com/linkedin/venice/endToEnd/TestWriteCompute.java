package com.linkedin.venice.endToEnd;

import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.ControllerResponse;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.hadoop.KafkaPushJob;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.schema.WriteComputeSchemaAdapter;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.writer.VeniceWriter;
import java.io.File;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.samza.system.SystemProducer;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.venice.ConfigKeys.*;
import static com.linkedin.venice.utils.TestPushUtils.*;
import static org.testng.Assert.*;


public class TestWriteCompute {

  private static final int STREAMING_RECORD_SIZE = 1024;

  private VeniceClusterWrapper veniceClusterWrapper;

  @BeforeClass
  public void setUp() {
    Properties extraProperties = new Properties();
    extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(1L));
    int maxMessageSizeInServer = STREAMING_RECORD_SIZE / 2;
    extraProperties.setProperty(VeniceWriter.MAX_SIZE_FOR_USER_PAYLOAD_PER_MESSAGE_IN_BYTES, Integer.toString(maxMessageSizeInServer));
    // N.B.: RF 2 with 2 servers is important, in order to test both the leader and follower code paths
    veniceClusterWrapper = ServiceFactory.getVeniceCluster(
        1,
        2,
        1,
        2,
        1000000,
        false,
        false,
        extraProperties);
  }

  @AfterClass
  public void cleanUp() {
    veniceClusterWrapper.close();
  }

  @Test
  public void testWriteComputeWithHybridLeaderFollowerLargeRecord() throws Exception {
    SystemProducer veniceProducer = null;

    try {
      long streamingRewindSeconds = 10L;
      long streamingMessageLag = 2L;

      String storeName = TestUtils.getUniqueString("write-compute-store");
      File inputDir = getTempDataDirectory();
      String inputDirPath = "file://" + inputDir.getAbsolutePath();
      // Records 1-100, id string to name record
      Schema recordSchema = writeSimpleAvroFileWithStringToRecordSchema(inputDir, true);
      Properties h2vProperties = defaultH2VProps(veniceClusterWrapper, inputDirPath, storeName);

      try (ControllerClient controllerClient = createStoreForJob(veniceClusterWrapper, recordSchema, h2vProperties);
          AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(
              ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(veniceClusterWrapper.getRandomRouterURL()))) {

        ControllerResponse response = controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
            .setHybridRewindSeconds(streamingRewindSeconds)
            .setHybridOffsetLagThreshold(streamingMessageLag)
            .setLeaderFollowerModel(true)
            .setChunkingEnabled(true)
            .setWriteComputationEnabled(true));

        Assert.assertFalse(response.isError());

        // H2V push
        runH2V(h2vProperties, 1, controllerClient);

        // Verify records (note, records 1-100 have been pushed)
        TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, true, () -> {
          try {
            for (int i = 1; i < 100; i++) {
              String key = String.valueOf(i);
              GenericRecord value = (GenericRecord)client.get(key).get();
              assertNotNull(value, "Key " + key + " should not be missing!");
              assertEquals(value.get("firstName").toString(), "first_name_" + key);
              assertEquals(value.get("lastName").toString(), "last_name_" + key);
            }
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });

        // Write a streaming record (large record)
        veniceProducer = getSamzaProducer(veniceClusterWrapper, storeName, Version.PushType.STREAM);
        String key = String.valueOf(101);
        Schema valueSchema = Schema.parse(NESTED_SCHEMA_STRING);
        GenericRecord value = new GenericData.Record(valueSchema);
        char[] chars = new char[STREAMING_RECORD_SIZE];
        Arrays.fill(chars, 'f');
        String firstName = new String(chars);
        Arrays.fill(chars, 'l');
        String lastName = new String(chars);
        value.put("firstName", firstName);
        value.put("lastName", lastName);
        sendStreamingRecord(veniceProducer, storeName, key, value);

        // Verify the streaming record
        TestUtils.waitForNonDeterministicAssertion(15, TimeUnit.SECONDS, () -> {
          try {
            GenericRecord retrievedValue = (GenericRecord)client.get(key).get();
            assertNotNull(retrievedValue, "Key " + key + " should not be missing!");
            assertEquals(retrievedValue.get("firstName").toString(), firstName);
            assertEquals(retrievedValue.get("lastName").toString(), lastName);
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });

        // Add derived schema
        Schema writeComputeSchema = WriteComputeSchemaAdapter.parse(NESTED_SCHEMA_STRING);
        controllerClient.addDerivedSchema(storeName, 1, writeComputeSchema.toString());

        Schema noOpSchema = writeComputeSchema.getField("lastName").schema().getTypes().get(0);
        GenericData.Record noOpRecord = new GenericData.Record(noOpSchema);

        // Update the record
        GenericRecord partialUpdateRecord = new GenericData.Record(writeComputeSchema);
        Arrays.fill(chars, 'u');
        String updatedFirstName = new String(chars);
        partialUpdateRecord.put("firstName", updatedFirstName);
        partialUpdateRecord.put("lastName", noOpRecord);
        sendStreamingRecord(veniceProducer, storeName, key, partialUpdateRecord);

        // Verify the update
        TestUtils.waitForNonDeterministicAssertion(15, TimeUnit.SECONDS, () -> {
          try {
            GenericRecord retrievedValue = (GenericRecord)client.get(key).get();
            assertNotNull(retrievedValue, "Key " + key + " should not be missing!");
            assertEquals(retrievedValue.get("firstName").toString(), updatedFirstName);
            assertEquals(retrievedValue.get("lastName").toString(), lastName);
          } catch (Exception e) {
            throw new VeniceException(e);
          }
        });
      }
    } finally {
      if (null != veniceProducer) {
        veniceProducer.stop();
      }
    }
  }

  /**
   * Blocking, waits for new version to go online
   */
  private static void runH2V(Properties h2vProperties, int expectedVersionNumber, ControllerClient controllerClient) throws Exception {
    String jobName = TestUtils.getUniqueString("write-compute-job-" + expectedVersionNumber);
    try (KafkaPushJob job = new KafkaPushJob(jobName, h2vProperties)) {
      job.run();
      TestUtils.waitForNonDeterministicCompletion(5, TimeUnit.SECONDS,
          () -> controllerClient.getStore((String) h2vProperties.get(KafkaPushJob.VENICE_STORE_NAME_PROP))
              .getStore().getCurrentVersion() == expectedVersionNumber);
    }
  }
}
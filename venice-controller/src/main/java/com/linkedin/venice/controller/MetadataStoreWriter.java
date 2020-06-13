package com.linkedin.venice.controller;

import com.linkedin.venice.common.StoreMetadataType;
import com.linkedin.venice.common.VeniceSystemStoreUtils;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.TopicManager;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.meta.systemstore.schemas.CurrentStoreStates;
import com.linkedin.venice.meta.systemstore.schemas.CurrentVersionStates;
import com.linkedin.venice.meta.systemstore.schemas.ETLStoreConfig;
import com.linkedin.venice.meta.systemstore.schemas.HybridStoreConfig;
import com.linkedin.venice.meta.systemstore.schemas.PartitionerConfig;
import com.linkedin.venice.meta.systemstore.schemas.StoreAttributes;
import com.linkedin.venice.meta.systemstore.schemas.StoreMetadataKey;
import com.linkedin.venice.meta.systemstore.schemas.StoreMetadataValue;
import com.linkedin.venice.meta.systemstore.schemas.StoreProperties;
import com.linkedin.venice.meta.systemstore.schemas.StoreVersionState;
import com.linkedin.venice.meta.systemstore.schemas.TargetVersionStates;
import com.linkedin.venice.serialization.avro.VeniceAvroKafkaSerializer;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.VeniceWriterFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.linkedin.venice.common.VeniceSystemStore.*;
import static com.linkedin.venice.schema.SchemaData.*;


public class MetadataStoreWriter {
  private final Map<String, VeniceWriter> metadataStoreWriterMap = new VeniceConcurrentHashMap<>();
  private final TopicManager topicManager;
  private final VeniceWriterFactory writerFactory;
  private final VeniceHelixAdmin admin;
  private final Map<String, Integer> storeMetadataSchemaIdMap = new VeniceConcurrentHashMap<>();

  public MetadataStoreWriter(TopicManager topicManager, VeniceWriterFactory writerFactory, VeniceHelixAdmin admin) {
    this.topicManager = topicManager;
    this.writerFactory = writerFactory;
    this.admin = admin;
  }

  public void writeStoreAttributes(String clusterName, String storeName, Store store) {
    VeniceWriter writer = prepareToWrite(clusterName, storeName);
    StoreMetadataKey key = new StoreMetadataKey();
    key.keyStrings = Arrays.asList(storeName);
    key.metadataType = StoreMetadataType.STORE_ATTRIBUTES.getValue();
    StoreMetadataValue value = new StoreMetadataValue();
    StoreAttributes storeAttributes = (StoreAttributes) StoreMetadataType.STORE_ATTRIBUTES.getNewInstance();
    storeAttributes.sourceCluster = clusterName;
    storeAttributes.otherClusters = new ArrayList<>();
    storeAttributes.configs = populateStoreProperties(store);
    value.metadataUnion = storeAttributes;
    value.timestamp = System.currentTimeMillis();
    writer.put(key, value, storeMetadataSchemaIdMap.get(clusterName));
  }

  public void writeTargetVersionStates(String clusterName, String storeName, List<Version> versions) {
    VeniceWriter writer = prepareToWrite(clusterName, storeName);
    StoreMetadataKey key = new StoreMetadataKey();
    key.keyStrings = Arrays.asList(storeName);
    key.metadataType = StoreMetadataType.TARGET_VERSION_STATES.getValue();
    StoreMetadataValue value = new StoreMetadataValue();
    TargetVersionStates targetVersionStates =
        (TargetVersionStates) StoreMetadataType.TARGET_VERSION_STATES.getNewInstance();
    targetVersionStates.targetVersionStates = parseVersionList(versions);
    value.metadataUnion = targetVersionStates;
    value.timestamp = System.currentTimeMillis();
    writer.put(key, value, storeMetadataSchemaIdMap.get(clusterName));
  }

  public void writeCurrentStoreStates(String clusterName, String storeName, Store store) {
    VeniceWriter writer = prepareToWrite(clusterName, storeName);
    StoreMetadataKey key = new StoreMetadataKey();
    key.keyStrings = Arrays.asList(storeName, clusterName);
    key.metadataType = StoreMetadataType.CURRENT_STORE_STATES.getValue();
    StoreMetadataValue value = new StoreMetadataValue();
    CurrentStoreStates currentStoreStates = (CurrentStoreStates) StoreMetadataType.CURRENT_STORE_STATES.getNewInstance();
    currentStoreStates.states = populateStoreProperties(store);
    value.metadataUnion = currentStoreStates;
    value.timestamp = System.currentTimeMillis();
    writer.put(key, value, storeMetadataSchemaIdMap.get(clusterName));
  }

  public void writeCurrentVersionStates(String clusterName, String storeName, List<Version> versions, int currentVersion) {
    VeniceWriter writer = prepareToWrite(clusterName, storeName);
    StoreMetadataKey key = new StoreMetadataKey();
    key.keyStrings = Arrays.asList(storeName, clusterName);
    key.metadataType = StoreMetadataType.CURRENT_VERSION_STATES.getValue();
    StoreMetadataValue value = new StoreMetadataValue();
    CurrentVersionStates currentVersionStates =
        (CurrentVersionStates) StoreMetadataType.CURRENT_VERSION_STATES.getNewInstance();
    currentVersionStates.currentVersionStates = parseVersionList(versions);
    currentVersionStates.currentVersion = currentVersion;
    value.metadataUnion = currentVersionStates;
    value.timestamp = System.currentTimeMillis();
    writer.put(key, value, storeMetadataSchemaIdMap.get(clusterName));
  }

  private VeniceWriter prepareToWrite(String clusterName, String storeName) {
    String rtTopic = Version.composeRealTimeTopic(VeniceSystemStoreUtils.getMetadataStoreName(storeName));
    if (!topicManager.containsTopicAndAllPartitionsAreOnline(rtTopic)) {
      throw new VeniceException("Realtime topic: " + rtTopic + " doesn't exist or some partitions are not online");
    }
    storeMetadataSchemaIdMap.computeIfAbsent(clusterName, k -> {
      int storeMetadataSchemaId = admin.getValueSchemaId(clusterName, METADATA_STORE.getPrefix(),
          StoreMetadataValue.SCHEMA$.toString());
      if (storeMetadataSchemaId == INVALID_VALUE_SCHEMA_ID) {
        throw new VeniceException("Invalid value schema for " + METADATA_STORE.getPrefix()
            + ", please make sure new value schemas are added to the store");
      }
      return storeMetadataSchemaId;
    });
    VeniceWriter writer = metadataStoreWriterMap.computeIfAbsent(storeName, k ->
        writerFactory.createVeniceWriter(rtTopic, new VeniceAvroKafkaSerializer(StoreMetadataKey.SCHEMA$.toString()),
            new VeniceAvroKafkaSerializer(StoreMetadataValue.SCHEMA$.toString())));
    return writer;
  }

  /**
   * Refactor this method to leverage write compute once it's enabled for store metadata system stores.
   */
  private StoreProperties populateStoreProperties(Store store) {
    StoreProperties storeProperties = new StoreProperties();
    storeProperties.accessControlled = store.isAccessControlled();
    storeProperties.backupStrategy = store.getBackupStrategy().toString();
    storeProperties.batchGetLimit = store.getBatchGetLimit();
    storeProperties.batchGetRouterCacheEnabled = store.isBatchGetRouterCacheEnabled();
    storeProperties.bootstrapToOnlineTimeoutInHours = store.getBootstrapToOnlineTimeoutInHours();
    storeProperties.chunkingEnabled = store.isChunkingEnabled();
    storeProperties.clientDecompressionEnabled = store.getClientDecompressionEnabled();
    storeProperties.compressionStrategy = store.getCompressionStrategy().toString();
    storeProperties.createdTime = store.getCreatedTime();
    // This property is mirrored for the sake of completeness in StoreAttributes and shouldn't be used. Rely on StoreCurrentStates instead.
    storeProperties.currentVersion = store.getCurrentVersion();
    storeProperties.enableReads = store.isEnableReads();
    storeProperties.enableWrites = store.isEnableWrites();
    if (store.getEtlStoreConfig() != null) {
      ETLStoreConfig etlStoreConfig = new ETLStoreConfig();
      etlStoreConfig.etledUserProxyAccount = store.getEtlStoreConfig().getEtledUserProxyAccount();
      etlStoreConfig.futureVersionETLEnabled = store.getEtlStoreConfig().isFutureVersionETLEnabled();
      etlStoreConfig.regularVersionETLEnabled = store.getEtlStoreConfig().isRegularVersionETLEnabled();
      storeProperties.etlStoreConfig = etlStoreConfig;
    } else {
      storeProperties.etlStoreConfig = null;
    }
    storeProperties.hybrid = store.isHybrid();
    if (store.getHybridStoreConfig() != null) {
      HybridStoreConfig hybridStoreConfig = new HybridStoreConfig();
      hybridStoreConfig.offsetLagThresholdToGoOnline = store.getHybridStoreConfig().getOffsetLagThresholdToGoOnline();
      hybridStoreConfig.rewindTimeInSeconds = store.getHybridStoreConfig().getRewindTimeInSeconds();
      storeProperties.hybridStoreConfig = hybridStoreConfig;
    } else {
      storeProperties.hybridStoreConfig = null;
    }
    storeProperties.hybridStoreDiskQuotaEnabled = store.isHybridStoreDiskQuotaEnabled();
    storeProperties.incrementalPushEnabled = store.isIncrementalPushEnabled();
    storeProperties.largestUsedVersionNumber = store.getLargestUsedVersionNumber();
    storeProperties.latestSuperSetValueSchemaId = store.getLatestSuperSetValueSchemaId();
    storeProperties.leaderFollowerModelEnabled = store.isLeaderFollowerModelEnabled();
    storeProperties.migrating = store.isMigrating();
    storeProperties.name = store.getName();
    storeProperties.numVersionsToPreserve = store.getNumVersionsToPreserve();
    storeProperties.offLinePushStrategy = store.getOffLinePushStrategy().toString();
    storeProperties.owner = store.getOwner();
    storeProperties.partitionCount = store.getPartitionCount();
    storeProperties.partitionerConfig = parsePartitionerConfig(store.getPartitionerConfig());
    storeProperties.readComputationEnabled = store.isReadComputationEnabled();
    storeProperties.readQuotaInCU = store.getReadQuotaInCU();
    storeProperties.readStrategy = store.getReadStrategy().toString();
    storeProperties.routingStrategy = store.getRoutingStrategy().toString();
    storeProperties.schemaAutoRegisterFromPushJobEnabled = store.isSchemaAutoRegisterFromPushJobEnabled();
    storeProperties.singleGetRouterCacheEnabled = store.isSingleGetRouterCacheEnabled();
    storeProperties.storageQuotaInByte = store.getStorageQuotaInByte();
    storeProperties.superSetSchemaAutoGenerationForReadComputeEnabled =
        store.isSuperSetSchemaAutoGenerationForReadComputeEnabled();
    storeProperties.systemStore = store.isSystemStore();
    storeProperties.nativeReplicationEnabled = store.isNativeReplicationEnabled();
    storeProperties.pushStreamSourceAddress = store.getPushStreamSourceAddress();

    return storeProperties;
  }

  private List<StoreVersionState> parseVersionList(List<Version> versions) {
    return versions.stream().map(version -> {
      StoreVersionState storeVersionState = new StoreVersionState();
      storeVersionState.versionNumber = version.getNumber();
      storeVersionState.pushJobId = version.getPushJobId();
      storeVersionState.partitionCount = version.getPartitionCount();
      storeVersionState.creationTime = version.getCreatedTime();
      storeVersionState.chunkingEnabled = version.isChunkingEnabled();
      storeVersionState.compressionStrategy = version.getCompressionStrategy().toString();
      storeVersionState.leaderFollowerModelEnabled = version.isLeaderFollowerModelEnabled();
      storeVersionState.pushType = version.getPushType().toString();
      storeVersionState.status = version.getStatus().toString();
      storeVersionState.partitionerConfig = parsePartitionerConfig(version.getPartitionerConfig());
      return storeVersionState;
    }).collect(Collectors.toList());
  }

  private PartitionerConfig parsePartitionerConfig(com.linkedin.venice.meta.PartitionerConfig inputPartitionerConfig) {
    if (inputPartitionerConfig != null) {
      PartitionerConfig partitionerConfig = new PartitionerConfig();
      partitionerConfig.amplificationFactor = inputPartitionerConfig.getAmplificationFactor();
      partitionerConfig.partitionerClass = inputPartitionerConfig.getPartitionerClass();
      partitionerConfig.partitionerParams =
          Utils.getCharSequenceMapFromStringMap(inputPartitionerConfig.getPartitionerParams());
      return partitionerConfig;
    }
    return null;
  }
}
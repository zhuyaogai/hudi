/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hudi.utilities.deltastreamer;

import org.apache.hudi.DataSourceReadOptions;
import org.apache.hudi.DataSourceWriteOptions;
import org.apache.hudi.HoodieSparkRecordMerger;
import org.apache.hudi.HoodieSparkUtils$;
import org.apache.hudi.client.SparkRDDWriteClient;
import org.apache.hudi.client.transaction.lock.InProcessLockProvider;
import org.apache.hudi.common.config.DFSPropertiesConfiguration;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.config.HoodieStorageConfig;
import org.apache.hudi.common.config.LockConfiguration;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieFailedWritesCleaningPolicy;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecord.HoodieRecordType;
import org.apache.hudi.common.model.HoodieReplaceCommitMetadata;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.model.OverwriteWithLatestAvroPayload;
import org.apache.hudi.common.model.PartialUpdateAvroPayload;
import org.apache.hudi.common.model.WriteConcurrencyMode;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.TableSchemaResolver;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineUtils;
import org.apache.hudi.common.table.view.HoodieTableFileSystemView;
import org.apache.hudi.common.testutils.HoodieTestDataGenerator;
import org.apache.hudi.common.testutils.HoodieTestUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.config.HoodieArchivalConfig;
import org.apache.hudi.config.HoodieCleanConfig;
import org.apache.hudi.config.HoodieCompactionConfig;
import org.apache.hudi.config.HoodieLockConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.TableNotFoundException;
import org.apache.hudi.hadoop.fs.HadoopFSUtils;
import org.apache.hudi.hive.HiveSyncConfig;
import org.apache.hudi.hive.HoodieHiveSyncClient;
import org.apache.hudi.keygen.ComplexKeyGenerator;
import org.apache.hudi.keygen.NonpartitionedKeyGenerator;
import org.apache.hudi.keygen.SimpleKeyGenerator;
import org.apache.hudi.metrics.Metrics;
import org.apache.hudi.metrics.MetricsReporterType;
import org.apache.hudi.utilities.DummySchemaProvider;
import org.apache.hudi.utilities.HoodieClusteringJob;
import org.apache.hudi.utilities.HoodieIndexer;
import org.apache.hudi.utilities.config.HoodieSchemaProviderConfig;
import org.apache.hudi.utilities.config.HoodieStreamerConfig;
import org.apache.hudi.utilities.config.SourceTestConfig;
import org.apache.hudi.utilities.schema.FilebasedSchemaProvider;
import org.apache.hudi.utilities.schema.SchemaProvider;
import org.apache.hudi.utilities.sources.CsvDFSSource;
import org.apache.hudi.utilities.sources.InputBatch;
import org.apache.hudi.utilities.sources.JdbcSource;
import org.apache.hudi.utilities.sources.JsonKafkaSource;
import org.apache.hudi.utilities.sources.ORCDFSSource;
import org.apache.hudi.utilities.sources.ParquetDFSSource;
import org.apache.hudi.utilities.sources.SqlSource;
import org.apache.hudi.utilities.sources.TestDataSource;
import org.apache.hudi.utilities.sources.TestParquetDFSSourceEmptyBatch;
import org.apache.hudi.utilities.streamer.HoodieStreamer;
import org.apache.hudi.utilities.streamer.NoNewDataTerminationStrategy;
import org.apache.hudi.utilities.testutils.JdbcTestUtils;
import org.apache.hudi.utilities.testutils.UtilitiesTestBase;
import org.apache.hudi.utilities.testutils.sources.DistributedTestDataSource;
import org.apache.hudi.utilities.transform.SqlQueryBasedTransformer;
import org.apache.hudi.utilities.transform.Transformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.spark.SparkException;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.api.java.UDF4;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.common.table.HoodieTableConfig.HIVE_STYLE_PARTITIONING_ENABLE;
import static org.apache.hudi.common.table.HoodieTableConfig.URL_ENCODE_PARTITIONING;
import static org.apache.hudi.common.table.HoodieTableMetaClient.METAFOLDER_NAME;
import static org.apache.hudi.config.HoodieWriteConfig.UPSERT_PARALLELISM_VALUE;
import static org.apache.hudi.config.metrics.HoodieMetricsConfig.METRICS_REPORTER_TYPE_VALUE;
import static org.apache.hudi.config.metrics.HoodieMetricsConfig.TURN_METRICS_ON;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_PARTITION_FIELDS;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_TABLE_NAME;
import static org.apache.hudi.utilities.UtilHelpers.EXECUTE;
import static org.apache.hudi.utilities.UtilHelpers.SCHEDULE;
import static org.apache.hudi.utilities.UtilHelpers.SCHEDULE_AND_EXECUTE;
import static org.apache.hudi.utilities.UtilHelpers.createMetaClient;
import static org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer.CHECKPOINT_KEY;
import static org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamerTestBase.TestHelpers.assertAtLeastNCommitsAfterRollback;
import static org.apache.hudi.utilities.schema.KafkaOffsetPostProcessor.KAFKA_SOURCE_OFFSET_COLUMN;
import static org.apache.hudi.utilities.schema.KafkaOffsetPostProcessor.KAFKA_SOURCE_PARTITION_COLUMN;
import static org.apache.hudi.utilities.schema.KafkaOffsetPostProcessor.KAFKA_SOURCE_TIMESTAMP_COLUMN;
import static org.apache.hudi.utilities.testutils.UtilitiesTestBase.Helpers.jsonifyRecordsByPartitions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Basic tests against {@link HoodieDeltaStreamer}, by issuing bulk_inserts, upserts, inserts. Check counts at the end.
 */
public class TestHoodieDeltaStreamer extends HoodieDeltaStreamerTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(TestHoodieDeltaStreamer.class);

  private void addRecordMerger(HoodieRecordType type, List<String> hoodieConfig) {
    if (type == HoodieRecordType.SPARK) {
      Map<String, String> opts = new HashMap<>();
      opts.put(HoodieWriteConfig.RECORD_MERGER_IMPLS.key(), HoodieSparkRecordMerger.class.getName());
      opts.put(HoodieStorageConfig.LOGFILE_DATA_BLOCK_FORMAT.key(), "parquet");
      for (Map.Entry<String, String> entry : opts.entrySet()) {
        hoodieConfig.add(String.format("%s=%s", entry.getKey(), entry.getValue()));
      }
      hudiOpts.putAll(opts);
    }
  }

  protected HoodieDeltaStreamer initialHoodieDeltaStreamer(String tableBasePath, int totalRecords, String asyncCluster, HoodieRecordType recordType) throws IOException {
    return initialHoodieDeltaStreamer(tableBasePath, totalRecords, asyncCluster, recordType, WriteOperationType.INSERT);
  }

  protected HoodieDeltaStreamer initialHoodieDeltaStreamer(String tableBasePath, int totalRecords, String asyncCluster, HoodieRecordType recordType,
                                                           WriteOperationType writeOperationType) throws IOException {
    return initialHoodieDeltaStreamer(tableBasePath, totalRecords, asyncCluster, recordType, writeOperationType, Collections.emptySet());
  }

  protected HoodieDeltaStreamer initialHoodieDeltaStreamer(String tableBasePath, int totalRecords, String asyncCluster, HoodieRecordType recordType,
                                                           WriteOperationType writeOperationType, Set<String> customConfigs) throws IOException {
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, writeOperationType);
    addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getTableServicesConfigs(totalRecords, "false", "", "", asyncCluster, ""));
    cfg.configs.addAll(getAllMultiWriterConfigs());
    customConfigs.forEach(config -> cfg.configs.add(config));
    return new HoodieDeltaStreamer(cfg, jsc);
  }

  protected HoodieClusteringJob initialHoodieClusteringJob(String tableBasePath, String clusteringInstantTime, Boolean runSchedule, String scheduleAndExecute, HoodieRecordType recordType) {
    return initialHoodieClusteringJob(tableBasePath, clusteringInstantTime, runSchedule, scheduleAndExecute, null, recordType);
  }

  protected HoodieClusteringJob initialHoodieClusteringJob(String tableBasePath, String clusteringInstantTime, Boolean runSchedule, String scheduleAndExecute) {
    return initialHoodieClusteringJob(tableBasePath, clusteringInstantTime, runSchedule, scheduleAndExecute, null, HoodieRecordType.AVRO);
  }

  protected HoodieClusteringJob initialHoodieClusteringJob(String tableBasePath, String clusteringInstantTime, Boolean runSchedule, String scheduleAndExecute,
                                                           Boolean retryLastFailedClusteringJob, HoodieRecordType recordType) {
    HoodieClusteringJob.Config scheduleClusteringConfig = buildHoodieClusteringUtilConfig(tableBasePath,
        clusteringInstantTime, runSchedule, scheduleAndExecute, retryLastFailedClusteringJob);
    addRecordMerger(recordType, scheduleClusteringConfig.configs);
    scheduleClusteringConfig.configs.addAll(getAllMultiWriterConfigs());
    return new HoodieClusteringJob(jsc, scheduleClusteringConfig);
  }

  @AfterEach
  public void perTestAfterEach() {
    testNum++;
  }

  @Test
  public void testProps() {
    TypedProperties props =
        new DFSPropertiesConfiguration(fs.getConf(), new Path(basePath + "/" + PROPS_FILENAME_TEST_SOURCE)).getProps();
    assertEquals(2, props.getInteger("hoodie.upsert.shuffle.parallelism"));
    assertEquals("_row_key", props.getString("hoodie.datasource.write.recordkey.field"));
    assertEquals("org.apache.hudi.utilities.deltastreamer.TestHoodieDeltaStreamer$TestGenerator",
        props.getString("hoodie.datasource.write.keygenerator.class"));
  }

  private static HoodieStreamer.Config getBaseConfig() {
    // Base config with all required fields
    HoodieStreamer.Config base = new HoodieStreamer.Config();
    base.targetBasePath = TGT_BASE_PATH_VALUE;
    base.tableType = TABLE_TYPE_VALUE;
    base.targetTableName = TARGET_TABLE_VALUE;
    return base;
  }

  /**
   * args for schema evolution test.
   *
   * @return
   */
  private static Stream<Arguments> schemaEvolArgs() {
    return Stream.of(
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), true, true, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), true, false, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), false, true, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), false, false, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), true, true, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), true, false, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), false, true, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), false, false, HoodieRecordType.AVRO),

        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), true, true, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), true, false, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), false, true, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), false, false, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), true, true, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), true, false, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), false, true, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), false, false, HoodieRecordType.SPARK));
  }

  private static Stream<Arguments> provideValidCliArgs() {

    HoodieStreamer.Config base = getBaseConfig();
    // String parameter
    HoodieStreamer.Config conf1 = getBaseConfig();
    conf1.baseFileFormat = BASE_FILE_FORMAT_VALUE;

    // Integer parameter
    HoodieStreamer.Config conf2 = getBaseConfig();
    conf2.sourceLimit = Long.parseLong(SOURCE_LIMIT_VALUE);

    // Boolean Parameter
    HoodieStreamer.Config conf3 = getBaseConfig();
    conf3.enableHiveSync = true;

    // ArrayList Parameter with 1 value
    HoodieStreamer.Config conf4 = getBaseConfig();
    conf4.configs = Arrays.asList(HOODIE_CONF_VALUE1);

    // ArrayList Parameter with comma separated values
    HoodieStreamer.Config conf5 = getBaseConfig();
    conf5.configs = Arrays.asList(HOODIE_CONF_VALUE2);

    // Multiple ArrayList values
    HoodieStreamer.Config conf6 = getBaseConfig();
    conf6.configs = Arrays.asList(HOODIE_CONF_VALUE1, HOODIE_CONF_VALUE2);

    // Super set of all cases
    HoodieStreamer.Config conf = getBaseConfig();
    conf.baseFileFormat = BASE_FILE_FORMAT_VALUE;
    conf.sourceLimit = Long.parseLong(SOURCE_LIMIT_VALUE);
    conf.enableHiveSync = true;
    conf.configs = Arrays.asList(HOODIE_CONF_VALUE1, HOODIE_CONF_VALUE2);

    String[] allConfig = new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE, SOURCE_LIMIT_PARAM,
        SOURCE_LIMIT_VALUE, TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
        BASE_FILE_FORMAT_PARAM, BASE_FILE_FORMAT_VALUE, ENABLE_HIVE_SYNC_PARAM, HOODIE_CONF_PARAM, HOODIE_CONF_VALUE1,
        HOODIE_CONF_PARAM, HOODIE_CONF_VALUE2};

    return Stream.of(
        // Base
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE}, base),
        // String
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            BASE_FILE_FORMAT_PARAM, BASE_FILE_FORMAT_VALUE}, conf1),
        // Integer
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            SOURCE_LIMIT_PARAM, SOURCE_LIMIT_VALUE}, conf2),
        // Boolean
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            ENABLE_HIVE_SYNC_PARAM}, conf3),
        // Array List 1
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            HOODIE_CONF_PARAM, HOODIE_CONF_VALUE1}, conf4),
        // Array List with comma
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            HOODIE_CONF_PARAM, HOODIE_CONF_VALUE2}, conf5),
        // Array list with multiple values
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            HOODIE_CONF_PARAM, HOODIE_CONF_VALUE1, HOODIE_CONF_PARAM, HOODIE_CONF_VALUE2}, conf6),
        // All
        Arguments.of(allConfig, conf)
    );
  }

  @ParameterizedTest
  @MethodSource("provideValidCliArgs")
  public void testValidCommandLineArgs(String[] args, HoodieStreamer.Config expected) {
    assertEquals(expected, HoodieDeltaStreamer.getConfig(args));
  }

  @Test
  public void testKafkaConnectCheckpointProvider() throws IOException {
    String tableBasePath = basePath + "/test_table";
    String bootstrapPath = basePath + "/kafka_topic1";
    String partitionPath = bootstrapPath + "/year=2016/month=05/day=01";
    String filePath = partitionPath + "/kafka_topic1+0+100+200.parquet";
    String checkpointProviderClass = "org.apache.hudi.utilities.checkpointing.KafkaConnectHdfsProvider";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeDropAllConfig(tableBasePath, WriteOperationType.UPSERT);
    TypedProperties props =
        new DFSPropertiesConfiguration(fs.getConf(), new Path(basePath + "/" + PROPS_FILENAME_TEST_SOURCE)).getProps();
    props.put("hoodie.deltastreamer.checkpoint.provider.path", bootstrapPath);
    cfg.initialCheckpointProvider = checkpointProviderClass;
    // create regular kafka connect hdfs dirs
    fs.mkdirs(new Path(bootstrapPath));
    fs.mkdirs(new Path(partitionPath));
    // generate parquet files using kafka connect naming convention
    HoodieTestDataGenerator dataGenerator = new HoodieTestDataGenerator();
    Helpers.saveParquetToDFS(Helpers.toGenericRecords(dataGenerator.generateInserts("000", 100)), new Path(filePath));
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(cfg, jsc, fs, jsc.hadoopConfiguration(), Option.ofNullable(props));
    assertEquals("kafka_topic1,0:200", deltaStreamer.getConfig().checkpoint);
  }

  @Test
  public void testPropsWithInvalidKeyGenerator() {
    Exception e = assertThrows(IOException.class, () -> {
      String tableBasePath = basePath + "/test_table_invalid_key_gen";
      HoodieDeltaStreamer deltaStreamer =
          new HoodieDeltaStreamer(TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT,
              Collections.singletonList(TripsWithDistanceTransformer.class.getName()), PROPS_FILENAME_TEST_INVALID, false), jsc);
      deltaStreamer.sync();
    }, "Should error out when setting the key generator class property to an invalid value");
    // expected
    LOG.warn("Expected error during getting the key generator", e);
    assertTrue(e.getMessage().contains("Could not load key generator class invalid"));
  }

  private static Stream<Arguments> provideInferKeyGenArgs() {
    return Stream.of(
        Arguments.of(
            PROPS_FILENAME_INFER_COMPLEX_KEYGEN,
            ComplexKeyGenerator.class.getName()),
        Arguments.of(
            PROPS_FILENAME_INFER_NONPARTITIONED_KEYGEN,
            NonpartitionedKeyGenerator.class.getName())
    );
  }

  @ParameterizedTest
  @MethodSource("provideInferKeyGenArgs")
  public void testInferKeyGenerator(String propsFilename,
                                    String expectedKeyGeneratorClassName) throws Exception {
    String[] splitNames = propsFilename.split("\\.");
    String tableBasePath = basePath + "/" + splitNames[0];
    HoodieDeltaStreamer deltaStreamer =
        new HoodieDeltaStreamer(TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT,
            Collections.singletonList(TripsWithDistanceTransformer.class.getName()),
            propsFilename, false), jsc);
    deltaStreamer.sync();
    HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder()
        .setConf(new Configuration()).setBasePath(tableBasePath).build();
    assertEquals(
        expectedKeyGeneratorClassName, metaClient.getTableConfig().getKeyGeneratorClassName());
    Dataset<Row> res = sqlContext.read().format("hudi").load(tableBasePath);
    assertEquals(1000, res.count());
  }

  @Test
  public void testTableCreation() throws Exception {
    Exception e = assertThrows(TableNotFoundException.class, () -> {
      fs.mkdirs(new Path(basePath + "/not_a_table"));
      HoodieDeltaStreamer deltaStreamer =
          new HoodieDeltaStreamer(TestHelpers.makeConfig(basePath + "/not_a_table", WriteOperationType.BULK_INSERT), jsc);
      deltaStreamer.sync();
    }, "Should error out when pointed out at a dir thats not a table");
    // expected
    LOG.debug("Expected error during table creation", e);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testTableCreationContainsHiveStylePartitioningEnable(boolean configFlag) throws Exception {
    String tablePath = basePath + "/url_encode_and_hive_style_partitioning_enable_" + configFlag;
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tablePath, WriteOperationType.INSERT);
    // Update DeltaStreamer configs for with parameterized test input
    cfg.configs.add(HIVE_STYLE_PARTITIONING_ENABLE.key() + "=" + configFlag);
    cfg.configs.add(URL_ENCODE_PARTITIONING.key() + "=" + configFlag);
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamer.getIngestionService().ingestOnce();
    // Create new metaClient from tablePath
    HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setConf(context.getHadoopConf().get())
        .setBasePath(tablePath).build();
    assertEquals(configFlag, Boolean.parseBoolean(metaClient.getTableConfig().getHiveStylePartitioningEnable()));
    assertEquals(configFlag, Boolean.parseBoolean(metaClient.getTableConfig().getUrlEncodePartitioning()));
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testBulkInsertsAndUpsertsWithBootstrap(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/test_table";

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    addRecordMerger(recordType, cfg.configs);
    syncAndAssertRecordCount(cfg, 1000, tableBasePath, "00000", 1);

    // No new data => no commits.
    cfg.sourceLimit = 0;
    syncAndAssertRecordCount(cfg, 1000, tableBasePath, "00000", 1);

    // upsert() #1
    cfg.sourceLimit = 2000;
    cfg.operation = WriteOperationType.UPSERT;
    syncAndAssertRecordCount(cfg, 1950, tableBasePath, "00001", 2);
    List<Row> counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1950, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    // Perform bootstrap with tableBasePath as source
    String bootstrapSourcePath = basePath + "/src_bootstrapped";
    Dataset<Row> sourceDf = sqlContext.read()
        .format("org.apache.hudi")
        .load(tableBasePath);
    // TODO(HUDI-4944): fix the test to use a partition column with slashes (`/`) included
    //  in the value.  Currently it fails the tests due to slash encoding.
    sourceDf.write().format("parquet").partitionBy("rider").save(bootstrapSourcePath);

    String newDatasetBasePath = basePath + "/test_dataset_bootstrapped";
    cfg.runBootstrap = true;
    cfg.configs.add(String.format("hoodie.bootstrap.base.path=%s", bootstrapSourcePath));
    cfg.configs.add(String.format("%s=%s", DataSourceWriteOptions.PARTITIONPATH_FIELD().key(), "rider"));
    cfg.configs.add(String.format("hoodie.datasource.write.keygenerator.class=%s", SimpleKeyGenerator.class.getName()));
    cfg.configs.add("hoodie.datasource.write.hive_style_partitioning=true");
    cfg.configs.add("hoodie.bootstrap.parallelism=5");
    cfg.targetBasePath = newDatasetBasePath;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    Dataset<Row> res = sqlContext.read().format("org.apache.hudi").load(newDatasetBasePath);
    LOG.info("Schema :");
    res.printSchema();

    assertRecordCount(1950, newDatasetBasePath, sqlContext);
    res.registerTempTable("bootstrapped");
    assertEquals(1950, sqlContext.sql("select distinct _hoodie_record_key from bootstrapped").count());
    // NOTE: To fetch record's count Spark will optimize the query fetching minimal possible amount
    //       of data, which might not provide adequate amount of test coverage
    sqlContext.sql("select * from bootstrapped").show();

    StructField[] fields = res.schema().fields();
    List<String> fieldNames = Arrays.asList(res.schema().fieldNames());
    List<String> expectedFieldNames = Arrays.asList(sourceDf.schema().fieldNames());
    assertEquals(expectedFieldNames.size(), fields.length);
    assertTrue(fieldNames.containsAll(HoodieRecord.HOODIE_META_COLUMNS));
    assertTrue(fieldNames.containsAll(expectedFieldNames));
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, bootstrapSourcePath);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, newDatasetBasePath);
  }

  @Test
  public void testModifiedTableConfigs() throws Exception {
    String tableBasePath = basePath + "/test_table_modified_configs";

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    syncAndAssertRecordCount(cfg, 1000, tableBasePath, "00000", 1);

    // No new data => no commits.
    cfg.sourceLimit = 0;
    syncAndAssertRecordCount(cfg, 1000, tableBasePath, "00000", 1);

    // add disallowed config update to recordkey field. An exception should be thrown
    cfg.sourceLimit = 2000;
    cfg.operation = WriteOperationType.UPSERT;
    cfg.configs.add(HoodieTableConfig.RECORDKEY_FIELDS.key() + "=differentval");
    assertThrows(HoodieException.class, () -> syncAndAssertRecordCount(cfg, 1000, tableBasePath, "00000", 1));
    List<Row> counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1000, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    //perform the upsert and now with the original config, the commit should go through
    HoodieDeltaStreamer.Config newCfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    newCfg.sourceLimit = 2000;
    newCfg.operation = WriteOperationType.UPSERT;
    syncAndAssertRecordCount(newCfg, 1950, tableBasePath, "00001", 2);
    List<Row> counts2 = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1950, counts2.stream().mapToLong(entry -> entry.getLong(1)).sum());
  }

  private void syncAndAssertRecordCount(HoodieDeltaStreamer.Config cfg, Integer expected, String tableBasePath, String metadata, Integer totalCommits) throws Exception {
    new HoodieDeltaStreamer(cfg, jsc).sync();
    assertRecordCount(expected, tableBasePath, sqlContext);
    assertDistanceCount(expected, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata(metadata, tableBasePath, fs, totalCommits);
  }

  // TODO add tests w/ disabled reconciliation
  @ParameterizedTest
  @MethodSource("schemaEvolArgs")
  public void testSchemaEvolution(String tableType, boolean useUserProvidedSchema, boolean useSchemaPostProcessor, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/test_table_schema_evolution" + tableType + "_" + useUserProvidedSchema + "_" + useSchemaPostProcessor;
    defaultSchemaProviderClassName = FilebasedSchemaProvider.class.getName();
    // Insert data produced with Schema A, pass Schema A
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, Collections.singletonList(TestIdentityTransformer.class.getName()),
        PROPS_FILENAME_TEST_SOURCE, false, true, false, null, tableType);
    addRecordMerger(recordType, cfg.configs);
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.source.schema.file=" + basePath + "/source.avsc");
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.target.schema.file=" + basePath + "/source.avsc");
    cfg.configs.add(DataSourceWriteOptions.RECONCILE_SCHEMA().key() + "=true");
    if (!useSchemaPostProcessor) {
      cfg.configs.add(HoodieSchemaProviderConfig.SPARK_AVRO_POST_PROCESSOR_ENABLE.key() + "=false");
    }
    new HoodieDeltaStreamer(cfg, jsc).sync();
    assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // Upsert data produced with Schema B, pass Schema B
    cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, Collections.singletonList(TripsWithEvolvedOptionalFieldTransformer.class.getName()),
        PROPS_FILENAME_TEST_SOURCE, false, true, false, null, tableType);
    addRecordMerger(recordType, cfg.configs);
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.source.schema.file=" + basePath + "/source.avsc");
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.target.schema.file=" + basePath + "/source_evolved.avsc");
    cfg.configs.add(DataSourceWriteOptions.RECONCILE_SCHEMA().key() + "=true");
    if (!useSchemaPostProcessor) {
      cfg.configs.add(HoodieSchemaProviderConfig.SPARK_AVRO_POST_PROCESSOR_ENABLE.key() + "=false");
    }
    new HoodieDeltaStreamer(cfg, jsc).sync();
    // out of 1000 new records, 500 are inserts, 450 are updates and 50 are deletes.
    assertRecordCount(1450, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00001", tableBasePath, fs, 2);
    List<Row> counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1450, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    sqlContext.read().format("org.apache.hudi").load(tableBasePath).createOrReplaceTempView("tmp_trips");
    long recordCount =
        sqlContext.sparkSession().sql("select * from tmp_trips where evoluted_optional_union_field is not NULL").count();
    assertEquals(950, recordCount);

    // Upsert data produced with Schema A, pass Schema B
    if (!useUserProvidedSchema) {
      defaultSchemaProviderClassName = TestFileBasedSchemaProviderNullTargetSchema.class.getName();
    }
    cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, Collections.singletonList(TestIdentityTransformer.class.getName()),
        PROPS_FILENAME_TEST_SOURCE, false, true, false, null, tableType);
    addRecordMerger(recordType, cfg.configs);
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.source.schema.file=" + basePath + "/source.avsc");
    if (useUserProvidedSchema) {
      cfg.configs.add("hoodie.deltastreamer.schemaprovider.target.schema.file=" + basePath + "/source_evolved.avsc");
    }
    if (!useSchemaPostProcessor) {
      cfg.configs.add(HoodieSchemaProviderConfig.SPARK_AVRO_POST_PROCESSOR_ENABLE.key() + "=false");
    }
    cfg.configs.add(DataSourceWriteOptions.RECONCILE_SCHEMA().key() + "=true");
    new HoodieDeltaStreamer(cfg, jsc).sync();
    // again, 1000 new records, 500 are inserts, 450 are updates and 50 are deletes.
    assertRecordCount(1900, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00002", tableBasePath, fs, 3);
    counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1900, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    TableSchemaResolver tableSchemaResolver = new TableSchemaResolver(HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(fs.getConf()).build());
    Schema tableSchema = tableSchemaResolver.getTableAvroSchema(false);
    assertNotNull(tableSchema);

    Schema expectedSchema;
    if (!useSchemaPostProcessor) {
      expectedSchema = new Schema.Parser().parse(fs.open(new Path(basePath + "/source_evolved.avsc")));
    } else {
      expectedSchema = new Schema.Parser().parse(fs.open(new Path(basePath + "/source_evolved_post_processed.avsc")));
    }
    assertEquals(expectedSchema, tableSchema);

    // clean up and reinit
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(HadoopFSUtils.getFs(cfg.targetBasePath, jsc.hadoopConfiguration()), basePath + "/" + PROPS_FILENAME_TEST_SOURCE);
    writeCommonPropsToFile(fs, basePath);
    defaultSchemaProviderClassName = FilebasedSchemaProvider.class.getName();
  }

  @Timeout(600)
  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testUpsertsCOWContinuousMode(HoodieRecordType recordType) throws Exception {
    testUpsertsContinuousMode(HoodieTableType.COPY_ON_WRITE, "continuous_cow", recordType);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testUpsertsCOW_ContinuousModeDisabled(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/non_continuous_cow";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.add(String.format("%s=%s", TURN_METRICS_ON.key(), "true"));
    cfg.configs.add(String.format("%s=%s", METRICS_REPORTER_TYPE_VALUE.key(), MetricsReporterType.INMEMORY.name()));
    cfg.continuousMode = false;
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();
    assertRecordCount(SQL_SOURCE_NUM_RECORDS, tableBasePath, sqlContext);
    assertFalse(Metrics.isInitialized(tableBasePath), "Metrics should be shutdown");
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Timeout(600)
  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO"})
  public void testUpsertsMORContinuousModeShutdownGracefully(HoodieRecordType recordType) throws Exception {
    testUpsertsContinuousMode(HoodieTableType.MERGE_ON_READ, "continuous_cow", true, recordType);
  }

  @Timeout(600)
  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testUpsertsMORContinuousMode(HoodieRecordType recordType) throws Exception {
    testUpsertsContinuousMode(HoodieTableType.MERGE_ON_READ, "continuous_mor", recordType);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testUpsertsMOR_ContinuousModeDisabled(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/non_continuous_mor";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.tableType = HoodieTableType.MERGE_ON_READ.name();
    cfg.configs.add(String.format("%s=%s", TURN_METRICS_ON.key(), "true"));
    cfg.configs.add(String.format("%s=%s", METRICS_REPORTER_TYPE_VALUE.key(), MetricsReporterType.INMEMORY.name()));
    cfg.continuousMode = false;
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();
    assertRecordCount(SQL_SOURCE_NUM_RECORDS, tableBasePath, sqlContext);
    assertFalse(Metrics.isInitialized(tableBasePath), "Metrics should be shutdown");
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  private void testUpsertsContinuousMode(HoodieTableType tableType, String tempDir, HoodieRecordType recordType) throws Exception {
    testUpsertsContinuousMode(tableType, tempDir, false, recordType);
  }

  private void testUpsertsContinuousMode(HoodieTableType tableType, String tempDir, boolean testShutdownGracefully, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/" + tempDir;
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 3000;
    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    if (testShutdownGracefully) {
      cfg.postWriteTerminationStrategyClass = NoNewDataTerminationStrategy.class.getName();
    }
    cfg.tableType = tableType.name();
    cfg.configs.add(String.format("%s=%d", SourceTestConfig.MAX_UNIQUE_RECORDS_PROP.key(), totalRecords));
    cfg.configs.add(String.format("%s=false", HoodieCleanConfig.AUTO_CLEAN.key()));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      if (tableType.equals(HoodieTableType.MERGE_ON_READ)) {
        TestHelpers.assertAtleastNDeltaCommits(5, tableBasePath, fs);
        TestHelpers.assertAtleastNCompactionCommits(2, tableBasePath, fs);
      } else {
        TestHelpers.assertAtleastNCompactionCommits(5, tableBasePath, fs);
      }
      assertRecordCount(totalRecords, tableBasePath, sqlContext);
      assertDistanceCount(totalRecords, tableBasePath, sqlContext);
      if (testShutdownGracefully) {
        TestDataSource.returnEmptyBatch = true;
      }
      return true;
    });
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  static void deltaStreamerTestRunner(HoodieDeltaStreamer ds, HoodieDeltaStreamer.Config cfg, Function<Boolean, Boolean> condition) throws Exception {
    deltaStreamerTestRunner(ds, cfg, condition, "single_ds_job");
  }

  static void deltaStreamerTestRunner(HoodieDeltaStreamer ds, HoodieDeltaStreamer.Config cfg, Function<Boolean, Boolean> condition, String jobId) throws Exception {
    Future dsFuture = Executors.newSingleThreadExecutor().submit(() -> {
      try {
        ds.sync();
      } catch (Exception ex) {
        LOG.warn("DS continuous job failed, hence not proceeding with condition check for " + jobId);
        throw new RuntimeException(ex.getMessage(), ex);
      }
    });
    TestHelpers.waitTillCondition(condition, dsFuture, 360);
    if (cfg != null && !cfg.postWriteTerminationStrategyClass.isEmpty()) {
      awaitDeltaStreamerShutdown(ds);
    } else {
      ds.shutdownGracefully();
      dsFuture.get();
    }
  }

  static void awaitDeltaStreamerShutdown(HoodieDeltaStreamer ds) throws InterruptedException {
    // await until deltastreamer shuts down on its own
    boolean shutDownRequested = false;
    int timeSoFar = 0;
    while (!shutDownRequested) {
      shutDownRequested = ds.getIngestionService().isShutdownRequested();
      Thread.sleep(500);
      timeSoFar += 500;
      if (timeSoFar > (2 * 60 * 1000)) {
        Assertions.fail("Deltastreamer should have shutdown by now");
      }
    }
    boolean shutdownComplete = false;
    while (!shutdownComplete) {
      shutdownComplete = ds.getIngestionService().isShutdown();
      Thread.sleep(500);
      timeSoFar += 500;
      if (timeSoFar > (2 * 60 * 1000)) {
        Assertions.fail("Deltastreamer should have shutdown by now");
      }
    }
  }

  static void deltaStreamerTestRunner(HoodieDeltaStreamer ds, Function<Boolean, Boolean> condition) throws Exception {
    deltaStreamerTestRunner(ds, null, condition);
  }

  @ParameterizedTest
  @CsvSource(value = {"AVRO", "SPARK"})
  public void testInlineClustering(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/inlineClustering";
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 3000;

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.MERGE_ON_READ.name();
    cfg.configs.addAll(getTableServicesConfigs(totalRecords, "false", "true", "2", "", ""));
    cfg.configs.add(String.format("%s=%s", "hoodie.datasource.write.row.writer.enable", "false"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);
      TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
      return true;
    });
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testDeltaSyncWithPendingClustering() throws Exception {
    String tableBasePath = basePath + "/inlineClusteringPending";
    // ingest data
    int totalRecords = 2000;
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    cfg.continuousMode = false;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.add(String.format("%s=%s", "hoodie.datasource.write.row.writer.enable", "false"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();
    // assert ingest successful
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);

    // schedule a clustering job to build a clustering plan and transition to inflight
    HoodieClusteringJob clusteringJob = initialHoodieClusteringJob(tableBasePath, null, false, "schedule");
    clusteringJob.cluster(0);
    HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tableBasePath).build();
    List<HoodieInstant> hoodieClusteringInstants = meta.getActiveTimeline().filterPendingReplaceTimeline().getInstants();
    HoodieInstant clusteringRequest = hoodieClusteringInstants.get(0);
    meta.getActiveTimeline().transitionReplaceRequestedToInflight(clusteringRequest, Option.empty());

    // do another ingestion with inline clustering enabled
    cfg.configs.addAll(getTableServicesConfigs(totalRecords, "false", "true", "2", "", ""));
    cfg.retryLastPendingInlineClusteringJob = true;
    HoodieDeltaStreamer ds2 = new HoodieDeltaStreamer(cfg, jsc);
    ds2.sync();
    String completeClusteringTimeStamp = meta.reloadActiveTimeline().getCompletedReplaceTimeline().lastInstant().get().getTimestamp();
    assertEquals(clusteringRequest.getTimestamp(), completeClusteringTimeStamp);
    TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);
    TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
  }

  @Test
  public void testDeltaSyncWithPendingCompaction() throws Exception {
    PARQUET_SOURCE_ROOT = basePath + "parquetFilesDfs" + testNum;
    int parquetRecordsCount = 100;
    HoodieTestDataGenerator dataGenerator = prepareParquetDFSFiles(parquetRecordsCount, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, false, null, null);
    TypedProperties extraProps = new TypedProperties();
    extraProps.setProperty("hoodie.compact.inline", "true");
    extraProps.setProperty("hoodie.compact.inline.max.delta.commits", "2");
    extraProps.setProperty("hoodie.datasource.write.table.type", "MERGE_ON_READ");
    extraProps.setProperty("hoodie.datasource.compaction.async.enable", "false");
    prepareParquetDFSSource(false, false, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "partition_path", "", extraProps);
    String tableBasePath = basePath + "test_parquet_table" + testNum;
    HoodieDeltaStreamer.Config deltaCfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, ParquetDFSSource.class.getName(),
        null, PROPS_FILENAME_TEST_PARQUET, false,
        false, 100000, false, null, "MERGE_ON_READ", "timestamp", null);
    deltaCfg.retryLastPendingInlineCompactionJob = false;

    // sync twice and trigger compaction
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(deltaCfg, jsc);
    deltaStreamer.sync();
    assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);
    prepareParquetDFSUpdates(100, PARQUET_SOURCE_ROOT, "2.parquet", false, null, null, dataGenerator, "001");
    deltaStreamer.sync();
    TestHelpers.assertAtleastNDeltaCommits(2, tableBasePath, fs);
    TestHelpers.assertAtleastNCompactionCommits(1, tableBasePath, fs);

    // delete compaction commit
    HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tableBasePath).build();
    HoodieTimeline timeline = meta.getActiveTimeline().getCommitTimeline().filterCompletedInstants();
    HoodieInstant commitInstant = timeline.lastInstant().get();
    String commitFileName = tableBasePath + "/.hoodie/" + commitInstant.getFileName();
    fs.delete(new Path(commitFileName), false);

    // sync again
    prepareParquetDFSUpdates(100, PARQUET_SOURCE_ROOT, "3.parquet", false, null, null, dataGenerator, "002");
    deltaStreamer = new HoodieDeltaStreamer(deltaCfg, jsc);
    deltaStreamer.sync();
    TestHelpers.assertAtleastNDeltaCommits(3, tableBasePath, fs);
    meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tableBasePath).build();
    timeline = meta.getActiveTimeline().getRollbackTimeline();
    assertEquals(1, timeline.getInstants().size());
  }

  @ParameterizedTest
  @CsvSource(value = {"true, AVRO", "true, SPARK", "false, AVRO", "false, SPARK"})
  public void testCleanerDeleteReplacedDataWithArchive(Boolean asyncClean, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/cleanerDeleteReplacedDataWithArchive" + asyncClean;

    int totalRecords = 3000;

    // Step 1 : Prepare and insert data without archival and cleaner.
    // Make sure that there are 6 commits including 2 replacecommits completed.
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getTableServicesConfigs(totalRecords, "false", "true", "2", "", ""));
    cfg.configs.add(String.format("%s=%s", HoodieCompactionConfig.PARQUET_SMALL_FILE_LIMIT.key(), "0"));
    cfg.configs.add(String.format("%s=%s", HoodieMetadataConfig.COMPACT_NUM_DELTA_COMMITS.key(), "1"));
    cfg.configs.add(String.format("%s=%s", HoodieWriteConfig.MARKERS_TYPE.key(), "DIRECT"));
    cfg.configs.add(String.format("%s=%s", "hoodie.datasource.write.row.writer.enable", "false"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtLeastNReplaceCommits(2, tableBasePath, fs);
      return true;
    });

    TestHelpers.assertAtLeastNCommits(6, tableBasePath, fs);
    TestHelpers.assertAtLeastNReplaceCommits(2, tableBasePath, fs);

    // Step 2 : Get the first replacecommit and extract the corresponding replaced file IDs.
    HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tableBasePath).build();
    HoodieTimeline replacedTimeline = meta.reloadActiveTimeline().getCompletedReplaceTimeline();
    Option<HoodieInstant> firstReplaceHoodieInstant = replacedTimeline.nthFromLastInstant(1);
    assertTrue(firstReplaceHoodieInstant.isPresent());

    Option<byte[]> firstReplaceHoodieInstantDetails = replacedTimeline.getInstantDetails(firstReplaceHoodieInstant.get());
    HoodieReplaceCommitMetadata firstReplaceMetadata = HoodieReplaceCommitMetadata.fromBytes(firstReplaceHoodieInstantDetails.get(), HoodieReplaceCommitMetadata.class);
    Map<String, List<String>> partitionToReplaceFileIds = firstReplaceMetadata.getPartitionToReplaceFileIds();
    String partitionName = null;
    List replacedFileIDs = null;
    for (Map.Entry entry : partitionToReplaceFileIds.entrySet()) {
      partitionName = String.valueOf(entry.getKey());
      replacedFileIDs = (List) entry.getValue();
    }

    assertNotNull(partitionName);
    assertNotNull(replacedFileIDs);

    // Step 3 : Based to replacedFileIDs , get the corresponding complete path.
    ArrayList<String> replacedFilePaths = new ArrayList<>();
    Path partitionPath = new Path(meta.getBasePath(), partitionName);
    RemoteIterator<LocatedFileStatus> hoodieFiles = meta.getFs().listFiles(partitionPath, true);
    while (hoodieFiles.hasNext()) {
      LocatedFileStatus f = hoodieFiles.next();
      String file = f.getPath().toUri().toString();
      for (Object replacedFileID : replacedFileIDs) {
        if (file.contains(String.valueOf(replacedFileID))) {
          replacedFilePaths.add(file);
        }
      }
    }

    assertFalse(replacedFilePaths.isEmpty());

    // Step 4 : Add commits with insert of 1 record and trigger sync/async cleaner and archive.
    List<String> configs = getTableServicesConfigs(1, "true", "true", "6", "", "");
    configs.add(String.format("%s=%s", HoodieCleanConfig.CLEANER_POLICY.key(), "KEEP_LATEST_COMMITS"));
    configs.add(String.format("%s=%s", HoodieCleanConfig.CLEANER_COMMITS_RETAINED.key(), "1"));
    configs.add(String.format("%s=%s", HoodieArchivalConfig.MIN_COMMITS_TO_KEEP.key(), "4"));
    configs.add(String.format("%s=%s", HoodieArchivalConfig.MAX_COMMITS_TO_KEEP.key(), "5"));
    configs.add(String.format("%s=%s", HoodieCleanConfig.ASYNC_CLEAN.key(), asyncClean));
    configs.add(String.format("%s=%s", HoodieMetadataConfig.COMPACT_NUM_DELTA_COMMITS.key(), "1"));
    configs.add(String.format("%s=%s", HoodieWriteConfig.MARKERS_TYPE.key(), "DIRECT"));
    if (asyncClean) {
      configs.add(String.format("%s=%s", HoodieWriteConfig.WRITE_CONCURRENCY_MODE.key(),
          WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL.name()));
      configs.add(String.format("%s=%s", HoodieCleanConfig.FAILED_WRITES_CLEANER_POLICY.key(),
          HoodieFailedWritesCleaningPolicy.LAZY.name()));
      configs.add(String.format("%s=%s", HoodieLockConfig.LOCK_PROVIDER_CLASS_NAME.key(),
          InProcessLockProvider.class.getName()));
    }
    addRecordMerger(recordType, configs);
    cfg.configs = configs;
    cfg.continuousMode = false;
    // timeline as of now. no cleaner and archival kicked in.
    // c1, c2, rc3, c4, c5, rc6,

    ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();
    // after 1 round of sync, timeline will be as follows
    // just before clean
    // c1, c2, rc3, c4, c5, rc6, c7
    // after clean
    // c1, c2, rc3, c4, c5, rc6, c7, c8.clean (earliest commit to retain is c7)
    // after archival (retain 4 commits)
    // c4, c5, rc6, c7, c8.clean

    // old code has 2 sync() calls. book-keeping the sequence for now.
    // after 2nd round of sync
    // just before clean
    // c4, c5, rc6, c7, c8.clean, c9
    // after clean
    // c4, c5, rc6, c7, c8.clean, c9, c10.clean (earliest commit to retain c9)
    // after archival
    // c5, rc6, c7, c8.clean, c9, c10.clean

    // Step 5 : FirstReplaceHoodieInstant should not be retained.
    long count = meta.reloadActiveTimeline().getCompletedReplaceTimeline().getInstantsAsStream().filter(instant -> firstReplaceHoodieInstant.get().equals(instant)).count();
    assertEquals(0, count);

    // Step 6 : All the replaced files in firstReplaceHoodieInstant should be deleted through sync/async cleaner.
    for (String replacedFilePath : replacedFilePaths) {
      assertFalse(meta.getFs().exists(new Path(replacedFilePath)));
    }
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  private List<String> getAllMultiWriterConfigs() {
    List<String> configs = new ArrayList<>();
    configs.add(String.format("%s=%s", HoodieLockConfig.LOCK_PROVIDER_CLASS_NAME.key(), InProcessLockProvider.class.getCanonicalName()));
    configs.add(String.format("%s=%s", LockConfiguration.LOCK_ACQUIRE_WAIT_TIMEOUT_MS_PROP_KEY, "3000"));
    configs.add(String.format("%s=%s", HoodieWriteConfig.WRITE_CONCURRENCY_MODE.key(), WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL.name()));
    configs.add(String.format("%s=%s", HoodieCleanConfig.FAILED_WRITES_CLEANER_POLICY.key(), HoodieFailedWritesCleaningPolicy.LAZY.name()));
    return configs;
  }

  private HoodieClusteringJob.Config buildHoodieClusteringUtilConfig(String basePath,
                                                                     String clusteringInstantTime,
                                                                     Boolean runSchedule) {
    return buildHoodieClusteringUtilConfig(basePath, clusteringInstantTime, runSchedule, null, null);
  }

  private HoodieClusteringJob.Config buildHoodieClusteringUtilConfig(String basePath,
                                                                     String clusteringInstantTime,
                                                                     Boolean runSchedule,
                                                                     String runningMode,
                                                                     Boolean retryLastFailedClusteringJob) {
    HoodieClusteringJob.Config config = new HoodieClusteringJob.Config();
    config.basePath = basePath;
    config.clusteringInstantTime = clusteringInstantTime;
    config.runSchedule = runSchedule;
    config.propsFilePath = UtilitiesTestBase.basePath + "/clusteringjob.properties";
    config.runningMode = runningMode;
    if (retryLastFailedClusteringJob != null) {
      config.retryLastFailedClusteringJob = retryLastFailedClusteringJob;
    }
    config.configs.add(String.format("%s=%s", "hoodie.datasource.write.row.writer.enable", "false"));
    return config;
  }

  private HoodieIndexer.Config buildIndexerConfig(String basePath,
                                                  String tableName,
                                                  String indexInstantTime,
                                                  String runningMode,
                                                  String indexTypes) {
    HoodieIndexer.Config config = new HoodieIndexer.Config();
    config.basePath = basePath;
    config.tableName = tableName;
    config.indexInstantTime = indexInstantTime;
    config.propsFilePath = UtilitiesTestBase.basePath + "/indexer.properties";
    config.runningMode = runningMode;
    config.indexTypes = indexTypes;
    return config;
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testHoodieIndexer(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncindexer";
    HoodieDeltaStreamer ds = initialHoodieDeltaStreamer(tableBasePath, 1000, "false", recordType, WriteOperationType.INSERT,
        Collections.singleton(HoodieMetadataConfig.ENABLE_METADATA_INDEX_COLUMN_STATS.key() + "=true"));

    deltaStreamerTestRunner(ds, (r) -> {
      TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);

      Option<String> scheduleIndexInstantTime = Option.empty();
      try {
        HoodieIndexer scheduleIndexingJob = new HoodieIndexer(jsc,
            buildIndexerConfig(tableBasePath, ds.getConfig().targetTableName, null, SCHEDULE, "COLUMN_STATS"));
        scheduleIndexInstantTime = scheduleIndexingJob.doSchedule();
      } catch (Exception e) {
        LOG.info("Schedule indexing failed", e);
        return false;
      }
      if (scheduleIndexInstantTime.isPresent()) {
        TestHelpers.assertPendingIndexCommit(tableBasePath, fs);
        LOG.info("Schedule indexing success, now build index with instant time " + scheduleIndexInstantTime.get());
        HoodieIndexer runIndexingJob = new HoodieIndexer(jsc,
            buildIndexerConfig(tableBasePath, ds.getConfig().targetTableName, scheduleIndexInstantTime.get(), EXECUTE, "COLUMN_STATS"));
        runIndexingJob.start(0);
        LOG.info("Metadata indexing success");
        TestHelpers.assertCompletedIndexCommit(tableBasePath, fs);
      } else {
        LOG.warn("Metadata indexing failed");
      }
      return true;
    });
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testHoodieAsyncClusteringJob(boolean shouldPassInClusteringInstantTime) throws Exception {
    String tableBasePath = basePath + "/asyncClusteringJob";
    HoodieDeltaStreamer ds = initialHoodieDeltaStreamer(tableBasePath, 3000, "false", HoodieRecordType.AVRO);
    CountDownLatch countDownLatch = new CountDownLatch(1);

    deltaStreamerTestRunner(ds, (r) -> {
      TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);
      countDownLatch.countDown();
      return true;
    });

    if (countDownLatch.await(2, TimeUnit.MINUTES)) {
      Option<String> scheduleClusteringInstantTime = Option.empty();
      try {
        HoodieClusteringJob scheduleClusteringJob =
            initialHoodieClusteringJob(tableBasePath, null, true, null);
        scheduleClusteringInstantTime = scheduleClusteringJob.doSchedule();
      } catch (Exception e) {
        LOG.warn("Schedule clustering failed", e);
        Assertions.fail("Schedule clustering failed", e);
      }
      if (scheduleClusteringInstantTime.isPresent()) {
        LOG.info("Schedule clustering success, now cluster with instant time " + scheduleClusteringInstantTime.get());
        HoodieClusteringJob.Config clusterClusteringConfig = buildHoodieClusteringUtilConfig(tableBasePath,
            shouldPassInClusteringInstantTime ? scheduleClusteringInstantTime.get() : null, false);
        HoodieClusteringJob clusterClusteringJob = new HoodieClusteringJob(jsc, clusterClusteringConfig);
        clusterClusteringJob.cluster(clusterClusteringConfig.retry);
        TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
        LOG.info("Cluster success");
      } else {
        LOG.warn("Clustering execution failed");
        Assertions.fail("Clustering execution failed");
      }
    } else {
      Assertions.fail("Deltastreamer should have completed 2 commits.");
    }
  }

  @Disabled("HUDI-6753")
  public void testAsyncClusteringServiceSparkRecordType() throws Exception {
    testAsyncClusteringService(HoodieRecordType.SPARK);
  }

  @Test
  public void testAsyncClusteringServiceAvroRecordType() throws Exception {
    testAsyncClusteringService(HoodieRecordType.AVRO);
  }

  private void testAsyncClusteringService(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClustering";
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 2000;

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getTableServicesConfigs(totalRecords, "false", "", "", "true", "3"));
    cfg.configs.add(String.format("%s=%s", "hoodie.datasource.write.row.writer.enable", "false"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
      return true;
    });
    // There should be 4 commits, one of which should be a replace commit
    TestHelpers.assertAtLeastNCommits(4, tableBasePath, fs);
    TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
    assertDistinctRecordCount(totalRecords, tableBasePath, sqlContext);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Timeout(600)
  @Test
  public void testAsyncClusteringServiceWithConflictsAvro() throws Exception {
    testAsyncClusteringServiceWithConflicts(HoodieRecordType.AVRO);
  }


  /**
   * When deltastreamer writes clashes with pending clustering, deltastreamer should keep retrying and eventually succeed(once clustering completes)
   * w/o failing mid way.
   *
   * @throws Exception
   */
  private void testAsyncClusteringServiceWithConflicts(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClusteringWithConflicts_" + recordType.name();
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 2000;

    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getTableServicesConfigs(totalRecords, "false", "", "", "true", "2"));
    cfg.configs.add(String.format("%s=%s", "hoodie.datasource.write.row.writer.enable", "false"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      // when pending clustering overlaps w/ incoming, incoming batch will fail and hence will result in rollback.
      // But eventually the batch should succeed. so, lets check for successful commits after a completed rollback.
      assertAtLeastNCommitsAfterRollback(1, 1, tableBasePath, fs);
      return true;
    });
    // There should be 4 commits, one of which should be a replace commit
    TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
    TestHelpers.assertAtLeastNCommits(3, tableBasePath, fs);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Timeout(600)
  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testAsyncClusteringServiceWithCompaction(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClusteringCompaction";
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 2000;

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.MERGE_ON_READ.name();
    cfg.configs.addAll(getTableServicesConfigs(totalRecords, "false", "", "", "true", "3"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtleastNCompactionCommits(2, tableBasePath, fs);
      TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
      return true;
    });
    // There should be 4 commits, one of which should be a replace commit
    TestHelpers.assertAtLeastNCommits(4, tableBasePath, fs);
    TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
    assertDistinctRecordCount(totalRecords, tableBasePath, sqlContext);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @ParameterizedTest
  @CsvSource(value = {"true, AVRO", "true, SPARK", "false, AVRO", "false, SPARK"})
  public void testAsyncClusteringJobWithRetry(boolean retryLastFailedClusteringJob, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClustering3";

    // ingest data
    int totalRecords = 3000;
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = false;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getTableServicesConfigs(totalRecords, "false", "false", "0", "false", "0"));
    cfg.configs.addAll(getAllMultiWriterConfigs());
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();

    // assert ingest successful
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);

    // schedule a clustering job to build a clustering plan
    HoodieClusteringJob schedule = initialHoodieClusteringJob(tableBasePath, null, false, "schedule");
    schedule.cluster(0);

    // do another ingestion
    HoodieDeltaStreamer ds2 = new HoodieDeltaStreamer(cfg, jsc);
    ds2.sync();

    // convert clustering request into inflight, Simulate the last clustering failed scenario
    HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tableBasePath).build();
    List<HoodieInstant> hoodieClusteringInstants = meta.getActiveTimeline().filterPendingReplaceTimeline().getInstants();
    HoodieInstant clusteringRequest = hoodieClusteringInstants.get(0);
    HoodieInstant hoodieInflightInstant = meta.getActiveTimeline().transitionReplaceRequestedToInflight(clusteringRequest, Option.empty());

    // trigger a scheduleAndExecute clustering job
    // when retryFailedClustering true => will rollback and re-execute failed clustering plan with same instant timestamp.
    // when retryFailedClustering false => will make and execute a new clustering plan with new instant timestamp.
    HoodieClusteringJob scheduleAndExecute = initialHoodieClusteringJob(tableBasePath, null, false, "scheduleAndExecute", retryLastFailedClusteringJob, recordType);
    scheduleAndExecute.cluster(0);

    String completeClusteringTimeStamp = meta.getActiveTimeline().reload().getCompletedReplaceTimeline().lastInstant().get().getTimestamp();

    if (retryLastFailedClusteringJob) {
      assertEquals(clusteringRequest.getTimestamp(), completeClusteringTimeStamp);
    } else {
      assertFalse(clusteringRequest.getTimestamp().equalsIgnoreCase(completeClusteringTimeStamp));
    }
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @ParameterizedTest
  @CsvSource(value = {"execute, AVRO", "schedule, AVRO", "scheduleAndExecute, AVRO", "execute, SPARK", "schedule, SPARK", "scheduleAndExecute, SPARK"})
  public void testHoodieAsyncClusteringJobWithScheduleAndExecute(String runningMode, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClustering2";
    HoodieDeltaStreamer ds = initialHoodieDeltaStreamer(tableBasePath, 3000, "false", recordType, WriteOperationType.BULK_INSERT);
    HoodieClusteringJob scheduleClusteringJob = initialHoodieClusteringJob(tableBasePath, null, true, runningMode, recordType);

    deltaStreamerTestRunner(ds, (r) -> {
      Exception exception = null;
      TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);
      try {
        int result = scheduleClusteringJob.cluster(0);
        if (result == 0) {
          LOG.info("Cluster success");
        } else {
          LOG.warn("Cluster failed");
          if (!runningMode.toLowerCase().equals(EXECUTE)) {
            return false;
          }
        }
      } catch (Exception e) {
        LOG.warn("ScheduleAndExecute clustering failed", e);
        exception = e;
        if (!runningMode.equalsIgnoreCase(EXECUTE)) {
          return false;
        }
      }
      switch (runningMode.toLowerCase()) {
        case SCHEDULE_AND_EXECUTE: {
          TestHelpers.assertAtLeastNReplaceCommits(2, tableBasePath, fs);
          return true;
        }
        case SCHEDULE: {
          TestHelpers.assertAtLeastNReplaceRequests(2, tableBasePath, fs);
          TestHelpers.assertNoReplaceCommits(tableBasePath, fs);
          return true;
        }
        case EXECUTE: {
          TestHelpers.assertNoReplaceCommits(tableBasePath, fs);
          return true;
        }
        default:
          throw new IllegalStateException("Unexpected value: " + runningMode);
      }
    });
    if (runningMode.toLowerCase(Locale.ROOT).equals(SCHEDULE_AND_EXECUTE)) {
      UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
    }
  }

  @Test
  public void testBulkInsertRowWriterNoSchemaProviderNoTransformer() throws Exception {
    testBulkInsertRowWriterMultiBatches(false, null);
  }

  @Test
  public void testBulkInsertRowWriterWithoutSchemaProviderAndTransformer() throws Exception {
    testBulkInsertRowWriterMultiBatches(false, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testBulkInsertRowWriterWithSchemaProviderAndNoTransformer() throws Exception {
    testBulkInsertRowWriterMultiBatches(true, null);
  }

  @Test
  public void testBulkInsertRowWriterWithSchemaProviderAndTransformer() throws Exception {
    testBulkInsertRowWriterMultiBatches(true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testBulkInsertRowWriterForEmptyBatch() throws Exception {
    testBulkInsertRowWriterMultiBatches(false, null, true);
  }

  private void testBulkInsertRowWriterMultiBatches(boolean useSchemaProvider, List<String> transformerClassNames) throws Exception {
    testBulkInsertRowWriterMultiBatches(useSchemaProvider, transformerClassNames, false);
  }

  private void testBulkInsertRowWriterMultiBatches(Boolean useSchemaProvider, List<String> transformerClassNames, boolean testEmptyBatch) throws Exception {
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfs" + testNum;
    int parquetRecordsCount = 100;
    boolean hasTransformer = transformerClassNames != null && !transformerClassNames.isEmpty();
    prepareParquetDFSFiles(parquetRecordsCount, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, false, null, null);
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "partition_path", "");

    String tableBasePath = basePath + "/test_parquet_table" + testNum;
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT, testEmptyBatch ? TestParquetDFSSourceEmptyBatch.class.getName()
            : ParquetDFSSource.class.getName(),
        transformerClassNames, PROPS_FILENAME_TEST_PARQUET, false,
        useSchemaProvider, 100000, false, null, null, "timestamp", null);
    cfg.configs.add(DataSourceWriteOptions.ENABLE_ROW_WRITER().key() + "=true");
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamer.sync();
    assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);
    deltaStreamer.shutdownGracefully();

    try {
      if (testEmptyBatch) {
        prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
            PARQUET_SOURCE_ROOT, false, "partition_path", "0");
        prepareParquetDFSFiles(100, PARQUET_SOURCE_ROOT, "2.parquet", false, null, null);
        deltaStreamer = new HoodieDeltaStreamer(cfg, jsc);
        deltaStreamer.sync();
        // since we mimic'ed empty batch, total records should be same as first sync().
        assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);
        HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(jsc.hadoopConfiguration()).build();

        // validate table schema fetches valid schema from last but one commit.
        TableSchemaResolver tableSchemaResolver = new TableSchemaResolver(metaClient);
        assertNotEquals(tableSchemaResolver.getTableAvroSchema(), Schema.create(Schema.Type.NULL).toString());
        // schema from latest commit and last but one commit should match
        compareLatestTwoSchemas(metaClient);
        prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
            PARQUET_SOURCE_ROOT, false, "partition_path", "");
        deltaStreamer.shutdownGracefully();
      }

      int recordsSoFar = 100;
      deltaStreamer = new HoodieDeltaStreamer(cfg, jsc);
      // add 3 more batches and ensure all commits succeed.
      for (int i = 2; i < 5; i++) {
        prepareParquetDFSFiles(100, PARQUET_SOURCE_ROOT, Integer.toString(i) + ".parquet", false, null, null);
        deltaStreamer.sync();
        assertRecordCount(recordsSoFar + (i - 1) * 100, tableBasePath, sqlContext);
        if (i == 2 || i == 4) { // this validation reloads the timeline. So, we are validating only for first and last batch.
          // validate commit metadata for all completed commits to have valid schema in extra metadata.
          HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(jsc.hadoopConfiguration()).build();
          metaClient.reloadActiveTimeline().getCommitsTimeline()
              .filterCompletedInstants().getInstants()
              .forEach(entry -> assertValidSchemaAndOperationTypeInCommitMetadata(
                  entry, metaClient, WriteOperationType.BULK_INSERT));
        }
      }
    } finally {
      deltaStreamer.shutdownGracefully();
    }
    testNum++;
  }

  @Test
  public void testBulkInsertRowWriterContinuousModeWithAsyncClustering() throws Exception {
    testBulkInsertRowWriterContinuousMode(false, null, false,
        getTableServicesConfigs(2000, "false", "", "", "true", "3"));
  }

  @Test
  public void testBulkInsertRowWriterContinuousModeWithInlineClustering() throws Exception {
    testBulkInsertRowWriterContinuousMode(false, null, false,
        getTableServicesConfigs(2000, "false", "true", "3", "false", ""));
  }

  private void testBulkInsertRowWriterContinuousMode(Boolean useSchemaProvider, List<String> transformerClassNames, boolean testEmptyBatch, List<String> customConfigs) throws Exception {
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfs" + testNum;
    int parquetRecordsCount = 100;
    boolean hasTransformer = transformerClassNames != null && !transformerClassNames.isEmpty();
    prepareParquetDFSFiles(parquetRecordsCount, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, false, null, null);
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "partition_path", testEmptyBatch ? "1" : "");

    // generate data asynchronously.
    Future inputGenerationFuture = Executors.newSingleThreadExecutor().submit(() -> {
      try {
        int counter = 2;
        while (counter < 100) { // lets keep going. if the test times out, we will cancel the future within finally. So, safe to generate 100 batches.
          LOG.info("Generating data for batch " + counter);
          prepareParquetDFSFiles(100, PARQUET_SOURCE_ROOT, Integer.toString(counter) + ".parquet", false, null, null);
          counter++;
          Thread.sleep(2000);
        }
      } catch (Exception ex) {
        LOG.warn("Input data generation failed", ex.getMessage());
        throw new RuntimeException(ex.getMessage(), ex);
      }
    });

    // initialize configs for continuous ds
    String tableBasePath = basePath + "/test_parquet_table" + testNum;
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT, testEmptyBatch ? TestParquetDFSSourceEmptyBatch.class.getName()
            : ParquetDFSSource.class.getName(),
        transformerClassNames, PROPS_FILENAME_TEST_PARQUET, false,
        useSchemaProvider, 100000, false, null, null, "timestamp", null);
    cfg.continuousMode = true;
    cfg.configs.add(DataSourceWriteOptions.ENABLE_ROW_WRITER().key() + "=true");
    cfg.configs.addAll(customConfigs);

    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    // trigger continuous DS and wait until 1 replace commit is complete.
    try {
      deltaStreamerTestRunner(ds, cfg, (r) -> {
        TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
        return true;
      });
      // There should be 4 commits, one of which should be a replace commit
      TestHelpers.assertAtLeastNCommits(4, tableBasePath, fs);
      TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
    } finally {
      // clean up resources
      ds.shutdownGracefully();
      inputGenerationFuture.cancel(true);
      UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
    }
    testNum++;
  }

  /**
   * Test Bulk Insert and upserts with hive syncing. Tests Hudi incremental processing using a 2 step pipeline The first
   * step involves using a SQL template to transform a source TEST-DATA-SOURCE ============================> HUDI TABLE
   * 1 ===============> HUDI TABLE 2 (incr-pull with transform) (incr-pull) Hudi Table 1 is synced with Hive.
   */
  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testBulkInsertsAndUpsertsWithSQLBasedTransformerFor2StepPipeline(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/" + recordType.toString() + "/test_table2";
    String downstreamTableBasePath = basePath + "/" + recordType.toString() + "/test_downstream_table2";

    // Initial bulk insert to ingest to first hudi table
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, true);
    addRecordMerger(recordType, cfg.configs);
    // NOTE: We should not have need to set below config, 'datestr' should have assumed date partitioning
    cfg.configs.add("hoodie.datasource.hive_sync.partition_fields=year,month,day");
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    assertRecordCount(1000, tableBasePath, sqlContext);
    assertDistanceCount(1000, tableBasePath, sqlContext);
    assertDistanceCountWithExactValue(1000, tableBasePath, sqlContext);
    String lastInstantForUpstreamTable = TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // Now incrementally pull from the above hudi table and ingest to second table
    HoodieDeltaStreamer.Config downstreamCfg =
        TestHelpers.makeConfigForHudiIncrSrc(tableBasePath, downstreamTableBasePath, WriteOperationType.BULK_INSERT,
            true, null);
    addRecordMerger(recordType, downstreamCfg.configs);
    new HoodieDeltaStreamer(downstreamCfg, jsc, fs, hiveServer.getHiveConf()).sync();
    assertRecordCount(1000, downstreamTableBasePath, sqlContext);
    assertDistanceCount(1000, downstreamTableBasePath, sqlContext);
    assertDistanceCountWithExactValue(1000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata(lastInstantForUpstreamTable, downstreamTableBasePath, fs, 1);

    // No new data => no commits for upstream table
    cfg.sourceLimit = 0;
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    assertRecordCount(1000, tableBasePath, sqlContext);
    assertDistanceCount(1000, tableBasePath, sqlContext);
    assertDistanceCountWithExactValue(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // with no change in upstream table, no change in downstream too when pulled.
    HoodieDeltaStreamer.Config downstreamCfg1 =
        TestHelpers.makeConfigForHudiIncrSrc(tableBasePath, downstreamTableBasePath,
            WriteOperationType.BULK_INSERT, true, DummySchemaProvider.class.getName());
    new HoodieDeltaStreamer(downstreamCfg1, jsc).sync();
    assertRecordCount(1000, downstreamTableBasePath, sqlContext);
    assertDistanceCount(1000, downstreamTableBasePath, sqlContext);
    assertDistanceCountWithExactValue(1000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata(lastInstantForUpstreamTable, downstreamTableBasePath, fs, 1);

    // upsert() #1 on upstream hudi table
    cfg.sourceLimit = 2000;
    cfg.operation = WriteOperationType.UPSERT;
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    assertRecordCount(1950, tableBasePath, sqlContext);
    assertDistanceCount(1950, tableBasePath, sqlContext);
    assertDistanceCountWithExactValue(1950, tableBasePath, sqlContext);
    lastInstantForUpstreamTable = TestHelpers.assertCommitMetadata("00001", tableBasePath, fs, 2);
    List<Row> counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1950, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    // Incrementally pull changes in upstream hudi table and apply to downstream table
    downstreamCfg =
        TestHelpers.makeConfigForHudiIncrSrc(tableBasePath, downstreamTableBasePath, WriteOperationType.UPSERT,
            false, null);
    addRecordMerger(recordType, downstreamCfg.configs);
    downstreamCfg.sourceLimit = 2000;
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();
    assertRecordCount(2000, downstreamTableBasePath, sqlContext);
    assertDistanceCount(2000, downstreamTableBasePath, sqlContext);
    assertDistanceCountWithExactValue(2000, downstreamTableBasePath, sqlContext);
    String finalInstant =
        TestHelpers.assertCommitMetadata(lastInstantForUpstreamTable, downstreamTableBasePath, fs, 2);
    counts = countsPerCommit(downstreamTableBasePath, sqlContext);
    assertEquals(2000, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    // Test Hive integration
    HiveSyncConfig hiveSyncConfig = getHiveSyncConfig(tableBasePath, "hive_trips");
    hiveSyncConfig.setValue(META_SYNC_PARTITION_FIELDS, "year,month,day");
    hiveSyncConfig.setHadoopConf(hiveTestService.getHiveConf());
    HoodieHiveSyncClient hiveClient = new HoodieHiveSyncClient(hiveSyncConfig);
    final String tableName = hiveSyncConfig.getString(META_SYNC_TABLE_NAME);
    assertTrue(hiveClient.tableExists(tableName), "Table " + tableName + " should exist");
    assertEquals(3, hiveClient.getAllPartitions(tableName).size(),
        "Table partitions should match the number of partitions we wrote");
    assertEquals(lastInstantForUpstreamTable,
        hiveClient.getLastCommitTimeSynced(tableName).get(),
        "The last commit that was synced should be updated in the TBLPROPERTIES");
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, downstreamTableBasePath);
  }

  @Test
  public void testNullSchemaProvider() {
    String tableBasePath = basePath + "/test_table";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, true,
        false, false, null, null);
    Exception e = assertThrows(HoodieException.class, () -> {
      new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    }, "Should error out when schema provider is not provided");
    LOG.debug("Expected error during reading data from source ", e);
    assertTrue(e.getMessage().contains("Please provide a valid schema provider class!"));
  }

  @Test
  public void testPayloadClassUpdate() throws Exception {
    String dataSetBasePath = basePath + "/test_dataset_mor_payload_class_update";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
        true, false, null, "MERGE_ON_READ");
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    assertRecordCount(1000, dataSetBasePath, sqlContext);

    //now create one more deltaStreamer instance and update payload class
    cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
        true, true, DummyAvroPayload.class.getName(), "MERGE_ON_READ");
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf());

    //now assert that hoodie.properties file now has updated payload class name
    HoodieTableMetaClient metaClient = createMetaClient(jsc, dataSetBasePath, false);
    assertEquals(metaClient.getTableConfig().getPayloadClass(), DummyAvroPayload.class.getName());
  }

  @Test
  public void testPartialPayloadClass() throws Exception {
    String dataSetBasePath = basePath + "/test_dataset_mor";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
        true, true, PartialUpdateAvroPayload.class.getName(), "MERGE_ON_READ");
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    assertRecordCount(1000, dataSetBasePath, sqlContext);

    //now assert that hoodie.properties file now has updated payload class name
    HoodieTableMetaClient metaClient = createMetaClient(jsc, dataSetBasePath, false);
    assertEquals(metaClient.getTableConfig().getPayloadClass(), PartialUpdateAvroPayload.class.getName());
  }

  @Test
  public void testPayloadClassUpdateWithCOWTable() throws Exception {
    String dataSetBasePath = basePath + "/test_dataset_cow";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
        true, false, null, null);
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    assertRecordCount(1000, dataSetBasePath, sqlContext);

    //now create one more deltaStreamer instance and update payload class
    cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
        true, true, DummyAvroPayload.class.getName(), null);
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf());

    //now assert that hoodie.properties file does not have payload class prop since it is a COW table
    Properties props = new Properties();
    String metaPath = dataSetBasePath + "/.hoodie/hoodie.properties";
    FileSystem fs = HadoopFSUtils.getFs(cfg.targetBasePath, jsc.hadoopConfiguration());
    try (InputStream inputStream = fs.open(new Path(metaPath))) {
      props.load(inputStream);
    }

    assertFalse(props.containsKey(HoodieTableConfig.PAYLOAD_CLASS_NAME.key()));
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testFilterDupes(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/test_dupes_table";

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    addRecordMerger(recordType, cfg.configs);
    new HoodieDeltaStreamer(cfg, jsc).sync();
    assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // Generate the same 1000 records + 1000 new ones for upsert
    cfg.filterDupes = true;
    cfg.sourceLimit = 2000;
    cfg.operation = WriteOperationType.INSERT;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    assertRecordCount(2000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00001", tableBasePath, fs, 2);
    // 1000 records for commit 00000 & 1000 for commit 00001
    List<Row> counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1000, counts.get(0).getLong(1));
    assertEquals(1000, counts.get(1).getLong(1));

    // Test with empty commits
    HoodieTableMetaClient mClient = HoodieTableMetaClient.builder().setConf(jsc.hadoopConfiguration()).setBasePath(tableBasePath).setLoadActiveTimelineOnLoad(true).build();
    HoodieInstant lastFinished = mClient.getCommitsTimeline().filterCompletedInstants().lastInstant().get();
    HoodieDeltaStreamer.Config cfg2 = TestHelpers.makeDropAllConfig(tableBasePath, WriteOperationType.UPSERT);
    addRecordMerger(recordType, cfg2.configs);
    cfg2.filterDupes = false;
    cfg2.sourceLimit = 2000;
    cfg2.operation = WriteOperationType.UPSERT;
    cfg2.configs.add(String.format("%s=false", HoodieCleanConfig.AUTO_CLEAN.key()));
    HoodieDeltaStreamer ds2 = new HoodieDeltaStreamer(cfg2, jsc);
    ds2.sync();
    mClient = HoodieTableMetaClient.builder().setConf(jsc.hadoopConfiguration()).setBasePath(tableBasePath).setLoadActiveTimelineOnLoad(true).build();
    HoodieInstant newLastFinished = mClient.getCommitsTimeline().filterCompletedInstants().lastInstant().get();
    assertTrue(HoodieTimeline.compareTimestamps(newLastFinished.getTimestamp(), HoodieTimeline.GREATER_THAN, lastFinished.getTimestamp()
    ));

    // Ensure it is empty
    HoodieCommitMetadata commitMetadata = HoodieCommitMetadata
        .fromBytes(mClient.getActiveTimeline().getInstantDetails(newLastFinished).get(), HoodieCommitMetadata.class);
    System.out.println("New Commit Metadata=" + commitMetadata);
    assertTrue(commitMetadata.getPartitionToWriteStats().isEmpty());

    // Try UPSERT with filterDupes true. Expect exception
    cfg2.filterDupes = true;
    cfg2.operation = WriteOperationType.UPSERT;
    try {
      new HoodieDeltaStreamer(cfg2, jsc).sync();
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("'--filter-dupes' needs to be disabled when '--op' is 'UPSERT' to ensure updates are not missed."));
    }
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testDistributedTestDataSource() {
    TypedProperties props = new TypedProperties();
    props.setProperty(SourceTestConfig.MAX_UNIQUE_RECORDS_PROP.key(), "1000");
    props.setProperty(SourceTestConfig.NUM_SOURCE_PARTITIONS_PROP.key(), "1");
    props.setProperty(SourceTestConfig.USE_ROCKSDB_FOR_TEST_DATAGEN_KEYS.key(), "true");
    DistributedTestDataSource distributedTestDataSource = new DistributedTestDataSource(props, jsc, sparkSession, null);
    InputBatch<JavaRDD<GenericRecord>> batch = distributedTestDataSource.fetchNext(Option.empty(), 10000000);
    batch.getBatch().get().cache();
    long c = batch.getBatch().get().count();
    assertEquals(1000, c);
  }

  private void prepareJsonKafkaDFSFiles(int numRecords, boolean createTopic, String topicName) {
    prepareJsonKafkaDFSFiles(numRecords, createTopic, topicName, 2);
  }

  private void prepareJsonKafkaDFSFiles(int numRecords, boolean createTopic, String topicName, int numPartitions) {
    if (createTopic) {
      try {
        testUtils.createTopic(topicName, numPartitions);
      } catch (TopicExistsException e) {
        // no op
      }
    }
    HoodieTestDataGenerator dataGenerator = new HoodieTestDataGenerator();
    testUtils.sendMessages(topicName, jsonifyRecordsByPartitions(dataGenerator.generateInsertsAsPerSchema("000", numRecords, HoodieTestDataGenerator.TRIP_SCHEMA), numPartitions));
  }

  private void testParquetDFSSource(boolean useSchemaProvider, List<String> transformerClassNames) throws Exception {
    testParquetDFSSource(useSchemaProvider, transformerClassNames, false);
  }

  private void testParquetDFSSource(boolean useSchemaProvider, List<String> transformerClassNames, boolean testEmptyBatch) throws Exception {
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfs" + testNum;
    int parquetRecordsCount = 10;
    boolean hasTransformer = transformerClassNames != null && !transformerClassNames.isEmpty();
    prepareParquetDFSFiles(parquetRecordsCount, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, false, null, null);
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "partition_path", "");

    String tableBasePath = basePath + "/test_parquet_table" + testNum;
    HoodieDeltaStreamer.Config cfg =
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, testEmptyBatch ? TestParquetDFSSourceEmptyBatch.class.getName()
                : ParquetDFSSource.class.getName(),
            transformerClassNames, PROPS_FILENAME_TEST_PARQUET, false,
            useSchemaProvider, 100000, false, null, null, "timestamp", null);
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamer.sync();
    assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);
    deltaStreamer.shutdownGracefully();

    if (testEmptyBatch) {
      prepareParquetDFSFiles(100, PARQUET_SOURCE_ROOT, "2.parquet", false, null, null);
      prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
          PARQUET_SOURCE_ROOT, false, "partition_path", "0");
      HoodieDeltaStreamer deltaStreamer1 = new HoodieDeltaStreamer(cfg, jsc);
      deltaStreamer1.sync();
      // since we mimic'ed empty batch, total records should be same as first sync().
      assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);
      HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(jsc.hadoopConfiguration()).build();

      // validate table schema fetches valid schema from last but one commit.
      TableSchemaResolver tableSchemaResolver = new TableSchemaResolver(metaClient);
      assertNotEquals(tableSchemaResolver.getTableAvroSchema(), Schema.create(Schema.Type.NULL).toString());
      // schema from latest commit and last but one commit should match
      compareLatestTwoSchemas(metaClient);
      prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
          PARQUET_SOURCE_ROOT, false, "partition_path", "");
      deltaStreamer1.shutdownGracefully();
    }

    // proceed w/ non empty batch.
    prepareParquetDFSFiles(100, PARQUET_SOURCE_ROOT, "3.parquet", false, null, null);
    deltaStreamer.sync();
    assertRecordCount(parquetRecordsCount + 100, tableBasePath, sqlContext);
    // validate commit metadata for all completed commits to have valid schema in extra metadata.
    HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(jsc.hadoopConfiguration()).build();
    metaClient.reloadActiveTimeline().getCommitsTimeline()
        .filterCompletedInstants().getInstants()
        .forEach(entry -> assertValidSchemaAndOperationTypeInCommitMetadata(
            entry, metaClient, WriteOperationType.INSERT));
    testNum++;
    deltaStreamer.shutdownGracefully();
  }

  private void assertValidSchemaAndOperationTypeInCommitMetadata(HoodieInstant instant,
                                                                 HoodieTableMetaClient metaClient,
                                                                 WriteOperationType operationType) {
    try {
      HoodieCommitMetadata commitMetadata = HoodieCommitMetadata
          .fromBytes(metaClient.getActiveTimeline().getInstantDetails(instant).get(), HoodieCommitMetadata.class);
      assertFalse(StringUtils.isNullOrEmpty(commitMetadata.getMetadata(HoodieCommitMetadata.SCHEMA_KEY)));
      assertEquals(operationType, commitMetadata.getOperationType());
    } catch (IOException ioException) {
      throw new HoodieException("Failed to parse commit metadata for " + instant.toString());
    }
  }

  private void compareLatestTwoSchemas(HoodieTableMetaClient metaClient) throws IOException {
    // schema from latest commit and last but one commit should match
    List<HoodieInstant> completedInstants = metaClient.getActiveTimeline().getWriteTimeline().filterCompletedInstants().getInstants();
    HoodieCommitMetadata commitMetadata1 = TimelineUtils.getCommitMetadata(completedInstants.get(0), metaClient.getActiveTimeline());
    HoodieCommitMetadata commitMetadata2 = TimelineUtils.getCommitMetadata(completedInstants.get(1), metaClient.getActiveTimeline());
    assertEquals(commitMetadata1.getMetadata(HoodieCommitMetadata.SCHEMA_KEY), commitMetadata2.getMetadata(HoodieCommitMetadata.SCHEMA_KEY));
  }

  private void testORCDFSSource(boolean useSchemaProvider, List<String> transformerClassNames) throws Exception {
    // prepare ORCDFSSource
    TypedProperties orcProps = new TypedProperties();

    // Properties used for testing delta-streamer with orc source
    orcProps.setProperty("include", "base.properties");
    orcProps.setProperty("hoodie.embed.timeline.server", "false");
    orcProps.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    orcProps.setProperty("hoodie.datasource.write.partitionpath.field", "partition_path");
    if (useSchemaProvider) {
      orcProps.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", basePath + "/" + "source.avsc");
      if (transformerClassNames != null) {
        orcProps.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", basePath + "/" + "target.avsc");
      }
    }
    orcProps.setProperty("hoodie.deltastreamer.source.dfs.root", ORC_SOURCE_ROOT);
    UtilitiesTestBase.Helpers.savePropsToDFS(orcProps, fs, basePath + "/" + PROPS_FILENAME_TEST_ORC);

    String tableBasePath = basePath + "/test_orc_source_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ORCDFSSource.class.getName(),
            transformerClassNames, PROPS_FILENAME_TEST_ORC, false,
            useSchemaProvider, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    assertRecordCount(ORC_NUM_RECORDS, tableBasePath, sqlContext);
    testNum++;
  }

  private void prepareJsonKafkaDFSSource(String propsFileName, String autoResetValue, String topicName) throws IOException {
    prepareJsonKafkaDFSSource(propsFileName, autoResetValue, topicName, null, false);
  }

  private void prepareJsonKafkaDFSSource(String propsFileName, String autoResetValue, String topicName, Map<String, String> extraProps, boolean shouldAddOffsets) throws IOException {
    // Properties used for testing delta-streamer with JsonKafka source
    TypedProperties props = new TypedProperties();
    populateAllCommonProps(props, basePath, testUtils.brokerAddress());
    props.setProperty("include", "base.properties");
    props.setProperty("hoodie.embed.timeline.server", "false");
    props.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    props.setProperty("hoodie.datasource.write.partitionpath.field", "driver");
    props.setProperty("hoodie.deltastreamer.source.dfs.root", JSON_KAFKA_SOURCE_ROOT);
    props.setProperty("hoodie.deltastreamer.source.kafka.topic", topicName);
    props.setProperty("hoodie.deltastreamer.source.kafka.checkpoint.type", kafkaCheckpointType);
    props.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", basePath + "/source_uber.avsc");
    props.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", basePath + "/target_uber.avsc");
    props.setProperty("auto.offset.reset", autoResetValue);
    if (extraProps != null && !extraProps.isEmpty()) {
      extraProps.forEach(props::setProperty);
    }
    props.setProperty(HoodieStreamerConfig.KAFKA_APPEND_OFFSETS.key(), Boolean.toString(shouldAddOffsets));
    UtilitiesTestBase.Helpers.savePropsToDFS(props, fs, basePath + "/" + propsFileName);
  }

  /**
   * Tests Deltastreamer with parquet dfs source and transitions to JsonKafkaSource.
   *
   * @param autoResetToLatest true if auto reset value to be set to LATEST. false to leave it as default(i.e. EARLIEST)
   * @throws Exception
   */
  private void testDeltaStreamerTransitionFromParquetToKafkaSource(boolean autoResetToLatest) throws Exception {
    // prep parquet source
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfsToKafka" + testNum;
    int parquetRecords = 10;
    prepareParquetDFSFiles(parquetRecords, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, true, HoodieTestDataGenerator.TRIP_SCHEMA, HoodieTestDataGenerator.AVRO_TRIP_SCHEMA);

    prepareParquetDFSSource(true, true, "source_uber.avsc", "target_uber.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "driver");
    // delta streamer w/ parquet source
    String tableBasePath = basePath + "/test_dfs_to_kafka" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_PARQUET, false,
            true, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    assertRecordCount(parquetRecords, tableBasePath, sqlContext);
    deltaStreamer.shutdownGracefully();

    // prep json kafka source
    topicName = "topic" + testNum;
    prepareJsonKafkaDFSFiles(JSON_KAFKA_NUM_RECORDS, true, topicName);
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, autoResetToLatest ? "latest" : "earliest", topicName);
    // delta streamer w/ json kafka source
    deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    // if auto reset value is set to LATEST, this all kafka records so far may not be synced.
    int totalExpectedRecords = parquetRecords + ((autoResetToLatest) ? 0 : JSON_KAFKA_NUM_RECORDS);
    assertRecordCount(totalExpectedRecords, tableBasePath, sqlContext);

    // verify 2nd batch to test LATEST auto reset value.
    prepareJsonKafkaDFSFiles(20, false, topicName);
    totalExpectedRecords += 20;
    deltaStreamer.sync();
    assertRecordCount(totalExpectedRecords, tableBasePath, sqlContext);
    testNum++;
  }

  @Test
  public void testJsonKafkaDFSSource() throws Exception {
    topicName = "topic" + testNum;
    prepareJsonKafkaDFSFiles(JSON_KAFKA_NUM_RECORDS, true, topicName);
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, "earliest", topicName);
    String tableBasePath = basePath + "/test_json_kafka_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    assertRecordCount(JSON_KAFKA_NUM_RECORDS, tableBasePath, sqlContext);

    int totalRecords = JSON_KAFKA_NUM_RECORDS;
    int records = 10;
    totalRecords += records;
    prepareJsonKafkaDFSFiles(records, false, topicName);
    deltaStreamer.sync();
    assertRecordCount(totalRecords, tableBasePath, sqlContext);
  }

  @Test
  public void testJsonKafkaDFSSourceWithOffsets() throws Exception {
    topicName = "topic" + testNum;
    int numRecords = 30;
    int numPartitions = 2;
    int recsPerPartition = numRecords / numPartitions;
    long beforeTime = Instant.now().toEpochMilli();
    prepareJsonKafkaDFSFiles(numRecords, true, topicName, numPartitions);
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, "earliest", topicName, null, true);
    String tableBasePath = basePath + "/test_json_kafka_offsets_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    sqlContext.clearCache();
    Dataset<Row> ds = sqlContext.read().format("org.apache.hudi").load(tableBasePath);
    assertEquals(numRecords, ds.count());
    //ensure that kafka partition column exists and is populated correctly
    for (int i = 0; i < numPartitions; i++) {
      assertEquals(recsPerPartition, ds.filter(KAFKA_SOURCE_PARTITION_COLUMN + "=" + i).count());
    }

    //ensure that kafka timestamp column exists and is populated correctly
    long afterTime = Instant.now().toEpochMilli();
    assertEquals(numRecords, ds.filter(KAFKA_SOURCE_TIMESTAMP_COLUMN + ">" + beforeTime).filter(KAFKA_SOURCE_TIMESTAMP_COLUMN + "<" + afterTime).count());


    //ensure that kafka offset column exists and is populated correctly
    sqlContext.read().format("org.apache.hudi").load(tableBasePath).col(KAFKA_SOURCE_OFFSET_COLUMN);
    for (int i = 0; i < recsPerPartition; i++) {
      for (int j = 0; j < numPartitions; j++) {
        //each offset partition pair should be unique
        assertEquals(1, ds.filter(KAFKA_SOURCE_OFFSET_COLUMN + "=" + i).filter(KAFKA_SOURCE_PARTITION_COLUMN + "=" + j).count());
      }
    }
  }

  @Test
  public void testKafkaTimestampType() throws Exception {
    topicName = "topic" + testNum;
    kafkaCheckpointType = "timestamp";
    prepareJsonKafkaDFSFiles(JSON_KAFKA_NUM_RECORDS, true, topicName);
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, "earliest", topicName);
    String tableBasePath = basePath + "/test_json_kafka_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null,
            null, "timestamp", String.valueOf(System.currentTimeMillis())), jsc);
    deltaStreamer.sync();
    assertRecordCount(JSON_KAFKA_NUM_RECORDS, tableBasePath, sqlContext);

    prepareJsonKafkaDFSFiles(JSON_KAFKA_NUM_RECORDS, false, topicName);
    deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null, null,
            "timestamp", String.valueOf(System.currentTimeMillis())), jsc);
    deltaStreamer.sync();
    assertRecordCount(JSON_KAFKA_NUM_RECORDS * 2, tableBasePath, sqlContext);
  }

  @Disabled("HUDI-6609")
  public void testDeltaStreamerMultiwriterCheckpoint() throws Exception {
    // prep parquet source
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesMultiCheckpoint" + testNum;
    int parquetRecords = 100;
    HoodieTestDataGenerator dataGenerator = prepareParquetDFSFiles(parquetRecords, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, true,
        HoodieTestDataGenerator.TRIP_SCHEMA, HoodieTestDataGenerator.AVRO_TRIP_SCHEMA);

    prepareParquetDFSSource(true, true, "source_uber.avsc", "target_uber.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "driver");

    // delta streamer w/ parquet source
    String tableBasePath = basePath + "/test_multi_checkpoint" + testNum;
    HoodieDeltaStreamer.Config parquetCfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
        Collections.emptyList(), PROPS_FILENAME_TEST_PARQUET, false,
        true, Integer.MAX_VALUE, false, null, null, "timestamp", null);
    parquetCfg.configs = new ArrayList<>();
    // parquetCfg.configs.add(MUTLI_WRITER_SOURCE_CHECKPOINT_ID.key() + "=parquet");
    //parquetCfg.continuousMode = false;
    HoodieDeltaStreamer parquetDs = new HoodieDeltaStreamer(parquetCfg, jsc);
    parquetDs.sync();
    assertRecordCount(100, tableBasePath, sqlContext);

    // prep json kafka source
    topicName = "topic" + testNum;
    prepareJsonKafkaDFSFiles(20, true, topicName);
    Map<String, String> kafkaExtraProps = new HashMap<>();
    // kafkaExtraProps.put(MUTLI_WRITER_SOURCE_CHECKPOINT_ID.key(), "kafka");
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, "earliest", topicName, kafkaExtraProps, false);
    // delta streamer w/ json kafka source
    HoodieDeltaStreamer kafkaDs = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, Integer.MAX_VALUE, false, null, null, "timestamp", null), jsc);
    kafkaDs.sync();
    int totalExpectedRecords = parquetRecords + 20;
    assertRecordCount(totalExpectedRecords, tableBasePath, sqlContext);
    //parquet again
    prepareParquetDFSUpdates(parquetRecords, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, true, HoodieTestDataGenerator.TRIP_SCHEMA, HoodieTestDataGenerator.AVRO_TRIP_SCHEMA,
        dataGenerator, "001");
    parquetDs = new HoodieDeltaStreamer(parquetCfg, jsc);
    parquetDs.sync();
    assertRecordCount(parquetRecords * 2 + 20, tableBasePath, sqlContext);

    HoodieTableMetaClient metaClient = HoodieTestUtils.init(jsc.hadoopConfiguration(), tableBasePath);
    List<HoodieInstant> instants = metaClient.getCommitsTimeline().getInstants();

    ObjectMapper objectMapper = new ObjectMapper();
    HoodieCommitMetadata commitMetadata = HoodieCommitMetadata
        .fromBytes(metaClient.getCommitsTimeline().getInstantDetails(instants.get(0)).get(), HoodieCommitMetadata.class);
    Map<String, String> checkpointVals = objectMapper.readValue(commitMetadata.getExtraMetadata().get(CHECKPOINT_KEY), Map.class);

    String parquetFirstcheckpoint = checkpointVals.get("parquet");
    assertNotNull(parquetFirstcheckpoint);
    commitMetadata = HoodieCommitMetadata
        .fromBytes(metaClient.getCommitsTimeline().getInstantDetails(instants.get(1)).get(), HoodieCommitMetadata.class);
    checkpointVals = objectMapper.readValue(commitMetadata.getExtraMetadata().get(CHECKPOINT_KEY), Map.class);
    String kafkaCheckpoint = checkpointVals.get("kafka");
    assertNotNull(kafkaCheckpoint);
    assertEquals(parquetFirstcheckpoint, checkpointVals.get("parquet"));

    commitMetadata = HoodieCommitMetadata
        .fromBytes(metaClient.getCommitsTimeline().getInstantDetails(instants.get(2)).get(), HoodieCommitMetadata.class);
    checkpointVals = objectMapper.readValue(commitMetadata.getExtraMetadata().get(CHECKPOINT_KEY), Map.class);
    String parquetSecondCheckpoint = checkpointVals.get("parquet");
    assertNotNull(parquetSecondCheckpoint);
    assertEquals(kafkaCheckpoint, checkpointVals.get("kafka"));
    assertTrue(Long.parseLong(parquetSecondCheckpoint) > Long.parseLong(parquetFirstcheckpoint));
    parquetDs.shutdownGracefully();
    kafkaDs.shutdownGracefully();
  }

  @Test
  public void testParquetSourceToKafkaSourceEarliestAutoResetValue() throws Exception {
    testDeltaStreamerTransitionFromParquetToKafkaSource(false);
  }

  @Test
  public void testParquetSourceToKafkaSourceLatestAutoResetValue() throws Exception {
    testDeltaStreamerTransitionFromParquetToKafkaSource(true);
  }

  @Test
  public void testParquetDFSSourceWithoutSchemaProviderAndNoTransformer() throws Exception {
    testParquetDFSSource(false, null);
  }

  @Test
  public void testParquetDFSSourceForEmptyBatch() throws Exception {
    testParquetDFSSource(false, null, true);
  }

  @Test
  public void testEmptyBatchWithNullSchemaValue() throws Exception {
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfs" + testNum;
    int parquetRecordsCount = 10;
    prepareParquetDFSFiles(parquetRecordsCount, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, false, null, null);
    prepareParquetDFSSource(false, false, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "partition_path", "0");

    String tableBasePath = basePath + "/test_parquet_table" + testNum;
    HoodieDeltaStreamer.Config config = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
        null, PROPS_FILENAME_TEST_PARQUET, false,
        false, 100000, false, null, null, "timestamp", null);
    HoodieDeltaStreamer deltaStreamer1 = new HoodieDeltaStreamer(config, jsc);
    deltaStreamer1.sync();
    assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);
    HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(jsc.hadoopConfiguration()).build();
    HoodieInstant firstCommit = metaClient.getActiveTimeline().lastInstant().get();
    deltaStreamer1.shutdownGracefully();

    prepareParquetDFSFiles(100, PARQUET_SOURCE_ROOT, "2.parquet", false, null, null);
    HoodieDeltaStreamer.Config updatedConfig = config;
    updatedConfig.schemaProviderClassName = NullValueSchemaProvider.class.getName();
    updatedConfig.sourceClassName = TestParquetDFSSourceEmptyBatch.class.getName();
    HoodieDeltaStreamer deltaStreamer2 = new HoodieDeltaStreamer(updatedConfig, jsc);
    deltaStreamer2.sync();
    // since we mimic'ed empty batch, total records should be same as first sync().
    assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);

    // validate schema is set in commit even if target schema returns null on empty batch
    TableSchemaResolver tableSchemaResolver = new TableSchemaResolver(metaClient);
    HoodieInstant secondCommit = metaClient.reloadActiveTimeline().lastInstant().get();
    Schema lastCommitSchema = tableSchemaResolver.getTableAvroSchema(secondCommit, true);
    assertNotEquals(firstCommit, secondCommit);
    assertNotEquals(lastCommitSchema, Schema.create(Schema.Type.NULL));
    deltaStreamer2.shutdownGracefully();
  }

  @Test
  public void testDeltaStreamerRestartAfterMissingHoodieProps() throws Exception {
    testDeltaStreamerRestartAfterMissingHoodieProps(true);
  }

  @Test
  public void testDeltaStreamerRestartAfterMissingHoodiePropsAfterValidCommit() throws Exception {
    testDeltaStreamerRestartAfterMissingHoodieProps(false);
  }

  private void testDeltaStreamerRestartAfterMissingHoodieProps(boolean testInitFailure) throws Exception {
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfs" + testNum;
    int parquetRecordsCount = 10;
    boolean hasTransformer = false;
    boolean useSchemaProvider = false;
    prepareParquetDFSFiles(parquetRecordsCount, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, false, null, null);
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "partition_path", "0");

    String tableBasePath = basePath + "/test_parquet_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, testInitFailure ? TestParquetDFSSourceEmptyBatch.class.getName() : ParquetDFSSource.class.getName(),
            null, PROPS_FILENAME_TEST_PARQUET, false,
            useSchemaProvider, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();

    if (testInitFailure) {
      FileStatus[] fileStatuses = fs.listStatus(new Path(tableBasePath + "/.hoodie/"));
      Arrays.stream(fileStatuses).filter(entry -> entry.getPath().getName().contains("commit") || entry.getPath().getName().contains("inflight")).forEach(entry -> {
        try {
          fs.delete(entry.getPath());
        } catch (IOException e) {
          LOG.warn("Failed to delete " + entry.getPath().toString(), e);
        }
      });
    }
    // delete hoodie.properties
    fs.delete(new Path(tableBasePath + "/.hoodie/hoodie.properties"));

    // restart the pipeline.
    if (testInitFailure) { // should succeed.
      deltaStreamer = new HoodieDeltaStreamer(
          TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
              null, PROPS_FILENAME_TEST_PARQUET, false,
              useSchemaProvider, 100000, false, null, null, "timestamp", null), jsc);
      deltaStreamer.sync();
      assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);
    } else {
      assertThrows(HoodieIOException.class, () -> new HoodieDeltaStreamer(
          TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
              null, PROPS_FILENAME_TEST_PARQUET, false,
              useSchemaProvider, 100000, false, null, null, "timestamp", null), jsc));
    }
    testNum++;
  }

  @Test
  public void testParquetDFSSourceWithoutSchemaProviderAndTransformer() throws Exception {
    testParquetDFSSource(false, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testParquetDFSSourceWithSourceSchemaFileAndNoTransformer() throws Exception {
    testParquetDFSSource(true, null);
  }

  @Test
  public void testParquetDFSSourceWithSchemaFilesAndTransformer() throws Exception {
    testParquetDFSSource(true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testORCDFSSourceWithoutSchemaProviderAndNoTransformer() throws Exception {
    // NOTE: Hudi doesn't support Orc in Spark < 3.0
    //       Please check HUDI-4496 for more details
    if (HoodieSparkUtils$.MODULE$.gteqSpark3_0()) {
      testORCDFSSource(false, null);
    }
  }

  @Test
  public void testORCDFSSourceWithSchemaProviderAndWithTransformer() throws Exception {
    // NOTE: Hudi doesn't support Orc in Spark < 3.0
    //       Please check HUDI-4496 for more details
    if (HoodieSparkUtils$.MODULE$.gteqSpark3_0()) {
      testORCDFSSource(true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
    }
  }

  private void prepareCsvDFSSource(
      boolean hasHeader, char sep, boolean useSchemaProvider, boolean hasTransformer) throws IOException {
    String sourceRoot = basePath + "/csvFiles";
    String recordKeyField = (hasHeader || useSchemaProvider) ? "_row_key" : "_c1";
    String partitionPath = (hasHeader || useSchemaProvider) ? "partition_path" : "_c2";

    // Properties used for testing delta-streamer with CSV source
    TypedProperties csvProps = new TypedProperties();
    csvProps.setProperty("include", "base.properties");
    csvProps.setProperty("hoodie.datasource.write.recordkey.field", recordKeyField);
    csvProps.setProperty("hoodie.datasource.write.partitionpath.field", partitionPath);
    if (useSchemaProvider) {
      csvProps.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", basePath + "/source-flattened.avsc");
      if (hasTransformer) {
        csvProps.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", basePath + "/target-flattened.avsc");
      }
    }
    csvProps.setProperty("hoodie.deltastreamer.source.dfs.root", sourceRoot);

    if (sep != ',') {
      if (sep == '\t') {
        csvProps.setProperty("hoodie.deltastreamer.csv.sep", "\\t");
      } else {
        csvProps.setProperty("hoodie.deltastreamer.csv.sep", Character.toString(sep));
      }
    }
    if (hasHeader) {
      csvProps.setProperty("hoodie.deltastreamer.csv.header", Boolean.toString(hasHeader));
    }

    UtilitiesTestBase.Helpers.savePropsToDFS(csvProps, fs, basePath + "/" + PROPS_FILENAME_TEST_CSV);

    String path = sourceRoot + "/1.csv";
    HoodieTestDataGenerator dataGenerator = new HoodieTestDataGenerator();
    UtilitiesTestBase.Helpers.saveCsvToDFS(
        hasHeader, sep,
        Helpers.jsonifyRecords(dataGenerator.generateInserts("000", CSV_NUM_RECORDS, true)),
        fs, path);
  }

  private void testCsvDFSSource(
      boolean hasHeader, char sep, boolean useSchemaProvider, List<String> transformerClassNames) throws Exception {
    prepareCsvDFSSource(hasHeader, sep, useSchemaProvider, transformerClassNames != null);
    String tableBasePath = basePath + "/test_csv_table" + testNum;
    String sourceOrderingField = (hasHeader || useSchemaProvider) ? "timestamp" : "_c0";
    HoodieDeltaStreamer deltaStreamer =
        new HoodieDeltaStreamer(TestHelpers.makeConfig(
            tableBasePath, WriteOperationType.INSERT, CsvDFSSource.class.getName(),
            transformerClassNames, PROPS_FILENAME_TEST_CSV, false,
            useSchemaProvider, 1000, false, null, null, sourceOrderingField, null), jsc);
    deltaStreamer.sync();
    assertRecordCount(CSV_NUM_RECORDS, tableBasePath, sqlContext);
    testNum++;
  }

  @Test
  public void testCsvDFSSourceWithHeaderWithoutSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files have header, the columns are separated by ',', the default separator
    // No schema provider is specified, no transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files
    testCsvDFSSource(true, ',', false, null);
  }

  @Test
  public void testCsvDFSSourceWithHeaderAndSepWithoutSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files have header, the columns are separated by '\t',
    // which is passed in through the Hudi CSV properties
    // No schema provider is specified, no transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files
    testCsvDFSSource(true, '\t', false, null);
  }

  @Test
  public void testCsvDFSSourceWithHeaderAndSepWithSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files have header, the columns are separated by '\t'
    // File schema provider is used, no transformer is applied
    // In this case, the source schema comes from the source Avro schema file
    testCsvDFSSource(true, '\t', true, null);
  }

  @Test
  public void testCsvDFSSourceWithHeaderAndSepWithoutSchemaProviderAndWithTransformer() throws Exception {
    // The CSV files have header, the columns are separated by '\t'
    // No schema provider is specified, transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files.
    // Target schema is determined based on the Dataframe after transformation
    testCsvDFSSource(true, '\t', false, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testCsvDFSSourceWithHeaderAndSepWithSchemaProviderAndTransformer() throws Exception {
    // The CSV files have header, the columns are separated by '\t'
    // File schema provider is used, transformer is applied
    // In this case, the source and target schema come from the Avro schema files
    testCsvDFSSource(true, '\t', true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testCsvDFSSourceNoHeaderWithoutSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files do not have header, the columns are separated by '\t',
    // which is passed in through the Hudi CSV properties
    // No schema provider is specified, no transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files
    // No CSV header and no schema provider at the same time are not recommended
    // as the column names are not informative
    testCsvDFSSource(false, '\t', false, null);
  }

  @Test
  public void testCsvDFSSourceNoHeaderWithSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files do not have header, the columns are separated by '\t'
    // File schema provider is used, no transformer is applied
    // In this case, the source schema comes from the source Avro schema file
    testCsvDFSSource(false, '\t', true, null);
  }

  @Test
  public void testCsvDFSSourceNoHeaderWithoutSchemaProviderAndWithTransformer() throws Exception {
    // The CSV files do not have header, the columns are separated by '\t'
    // No schema provider is specified, transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files.
    // Target schema is determined based on the Dataframe after transformation
    // No CSV header and no schema provider at the same time are not recommended,
    // as the transformer behavior may be unexpected
    Exception e = assertThrows(AnalysisException.class, () -> {
      testCsvDFSSource(false, '\t', false, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
    }, "Should error out when doing the transformation.");
    LOG.debug("Expected error during transformation", e);
    // first version for Spark >= 3.3, the second one is for Spark < 3.3
    assertTrue(e.getMessage().contains("Column 'begin_lat' does not exist. Did you mean one of the following?")
        || e.getMessage().contains("cannot resolve 'begin_lat' given input columns:"));
  }

  @Test
  public void testCsvDFSSourceNoHeaderWithSchemaProviderAndTransformer() throws Exception {
    // The CSV files do not have header, the columns are separated by '\t'
    // File schema provider is used, transformer is applied
    // In this case, the source and target schema come from the Avro schema files
    testCsvDFSSource(false, '\t', true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  private void prepareSqlSource() throws IOException {
    String sourceRoot = basePath + "sqlSourceFiles";
    TypedProperties sqlSourceProps = new TypedProperties();
    sqlSourceProps.setProperty("include", "base.properties");
    sqlSourceProps.setProperty("hoodie.embed.timeline.server", "false");
    sqlSourceProps.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    sqlSourceProps.setProperty("hoodie.datasource.write.partitionpath.field", "partition_path");
    sqlSourceProps.setProperty("hoodie.deltastreamer.source.sql.sql.query", "select * from test_sql_table");

    UtilitiesTestBase.Helpers.savePropsToDFS(sqlSourceProps, fs, basePath + "/" + PROPS_FILENAME_TEST_SQL_SOURCE);

    // Data generation
    HoodieTestDataGenerator dataGenerator = new HoodieTestDataGenerator();
    generateSqlSourceTestTable(sourceRoot, "1", "1000", SQL_SOURCE_NUM_RECORDS, dataGenerator);
  }

  private void generateSqlSourceTestTable(String dfsRoot, String filename, String instantTime, int n, HoodieTestDataGenerator dataGenerator) throws IOException {
    Path path = new Path(dfsRoot, filename);
    Helpers.saveParquetToDFS(Helpers.toGenericRecords(dataGenerator.generateInserts(instantTime, n, false)), path);
    sparkSession.read().parquet(dfsRoot).createOrReplaceTempView("test_sql_table");
  }

  @Test
  public void testSqlSourceSource() throws Exception {
    prepareSqlSource();
    String tableBasePath = basePath + "/test_sql_source_table" + testNum++;
    HoodieDeltaStreamer deltaStreamer =
        new HoodieDeltaStreamer(TestHelpers.makeConfig(
            tableBasePath, WriteOperationType.INSERT, SqlSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_SQL_SOURCE, false,
            false, 1000, false, null, null, "timestamp", null, true), jsc);
    deltaStreamer.sync();
    assertRecordCount(SQL_SOURCE_NUM_RECORDS, tableBasePath, sqlContext);
  }

  @Disabled
  @Test
  public void testJdbcSourceIncrementalFetchInContinuousMode() {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:test_mem", "test", "jdbc")) {
      TypedProperties props = new TypedProperties();
      props.setProperty("hoodie.deltastreamer.jdbc.url", "jdbc:h2:mem:test_mem");
      props.setProperty("hoodie.deltastreamer.jdbc.driver.class", "org.h2.Driver");
      props.setProperty("hoodie.deltastreamer.jdbc.user", "test");
      props.setProperty("hoodie.deltastreamer.jdbc.password", "jdbc");
      props.setProperty("hoodie.deltastreamer.jdbc.table.name", "triprec");
      props.setProperty("hoodie.deltastreamer.jdbc.incr.pull", "true");
      props.setProperty("hoodie.deltastreamer.jdbc.table.incr.column.name", "id");

      props.setProperty("hoodie.datasource.write.recordkey.field", "ID");
      props.setProperty("hoodie.datasource.write.partitionpath.field", "partition_path");

      UtilitiesTestBase.Helpers.savePropsToDFS(props, fs, basePath + "/test-jdbc-source.properties");

      int numRecords = 1000;
      int sourceLimit = 100;
      String tableBasePath = basePath + "/triprec";
      HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, JdbcSource.class.getName(),
          null, "test-jdbc-source.properties", false,
          false, sourceLimit, false, null, null, "timestamp", null);
      cfg.continuousMode = true;
      // Add 1000 records
      JdbcTestUtils.clearAndInsert("000", numRecords, connection, new HoodieTestDataGenerator(), props);

      HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(cfg, jsc);
      deltaStreamerTestRunner(deltaStreamer, cfg, (r) -> {
        TestHelpers.assertAtleastNCompactionCommits(numRecords / sourceLimit + ((numRecords % sourceLimit == 0) ? 0 : 1), tableBasePath, fs);
        assertRecordCount(numRecords, tableBasePath, sqlContext);
        return true;
      });
    } catch (Exception e) {
      fail(e.getMessage());
    }
  }

  @Test
  public void testHoodieIncrFallback() throws Exception {
    String tableBasePath = basePath + "/incr_test_table";
    String downstreamTableBasePath = basePath + "/incr_test_downstream_table";

    insertInTable(tableBasePath, 1, WriteOperationType.BULK_INSERT);
    HoodieDeltaStreamer.Config downstreamCfg =
        TestHelpers.makeConfigForHudiIncrSrc(tableBasePath, downstreamTableBasePath,
            WriteOperationType.BULK_INSERT, true, null);
    downstreamCfg.configs.add("hoodie.deltastreamer.source.hoodieincr.num_instants=1");
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();

    insertInTable(tableBasePath, 9, WriteOperationType.UPSERT);
    //No change as this fails with Path not exist error
    assertThrows(SparkException.class, () -> new HoodieDeltaStreamer(downstreamCfg, jsc).sync());
    assertRecordCount(1000, downstreamTableBasePath, sqlContext);

    if (downstreamCfg.configs == null) {
      downstreamCfg.configs = new ArrayList<>();
    }

    // Remove source.hoodieincr.num_instants config
    downstreamCfg.configs.remove(downstreamCfg.configs.size() - 1);
    downstreamCfg.configs.add(DataSourceReadOptions.INCREMENTAL_FALLBACK_TO_FULL_TABLE_SCAN().key() + "=true");
    //Adding this conf to make testing easier :)
    downstreamCfg.configs.add("hoodie.deltastreamer.source.hoodieincr.num_instants=10");
    downstreamCfg.operation = WriteOperationType.UPSERT;
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();

    long baseTableRecords = sqlContext.read().format("org.apache.hudi").load(tableBasePath).count();
    long downStreamTableRecords = sqlContext.read().format("org.apache.hudi").load(downstreamTableBasePath).count();
    assertEquals(baseTableRecords, downStreamTableRecords);
  }

  private void insertInTable(String tableBasePath, int count, WriteOperationType operationType) throws Exception {
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, operationType,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false);
    if (cfg.configs == null) {
      cfg.configs = new ArrayList<>();
    }
    cfg.configs.add("hoodie.cleaner.commits.retained=2");
    cfg.configs.add("hoodie.keep.min.commits=4");
    cfg.configs.add("hoodie.keep.max.commits=5");
    cfg.configs.add("hoodie.test.source.generate.inserts=true");

    for (int i = 0; i < count; i++) {
      new HoodieDeltaStreamer(cfg, jsc).sync();
    }
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testInsertOverwrite(HoodieRecordType recordType) throws Exception {
    testDeltaStreamerWithSpecifiedOperation(basePath + "/insert_overwrite", WriteOperationType.INSERT_OVERWRITE, recordType);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testInsertOverwriteTable(HoodieRecordType recordType) throws Exception {
    testDeltaStreamerWithSpecifiedOperation(basePath + "/insert_overwrite_table", WriteOperationType.INSERT_OVERWRITE_TABLE, recordType);
  }

  @Disabled("Local run passing; flaky in CI environment.")
  @Test
  public void testDeletePartitions() throws Exception {
    prepareParquetDFSSource(false, false, "source.avsc", "target.avsc",
        PROPS_FILENAME_TEST_PARQUET, PARQUET_SOURCE_ROOT, false, "partition_path");
    String tableBasePath = basePath + "/test_parquet_table" + testNum;

    // There should be fileIDs in the partition being deleted
    assertFalse(getAllFileIDsInTable(tableBasePath, Option.of(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH)).isEmpty());

    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
            null, PROPS_FILENAME_TEST_PARQUET, false,
            false, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    assertRecordCount(PARQUET_NUM_RECORDS, tableBasePath, sqlContext);
    testNum++;

    prepareParquetDFSFiles(PARQUET_NUM_RECORDS, PARQUET_SOURCE_ROOT);
    prepareParquetDFSSource(false, false);
    // set write operation to DELETE_PARTITION and add transformer to filter only for records with partition HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION
    deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.DELETE_PARTITION, ParquetDFSSource.class.getName(),
            Collections.singletonList(TestSpecificPartitionTransformer.class.getName()), PROPS_FILENAME_TEST_PARQUET, false,
            false, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    // No records should match the HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION.
    assertNoPartitionMatch(tableBasePath, sqlContext, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH);

    // There should not be any fileIDs in the deleted partition
    assertTrue(getAllFileIDsInTable(tableBasePath, Option.of(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH)).isEmpty());
  }

  @Test
  public void testToSortedTruncatedStringSecretsMasked() {
    TypedProperties props =
        new DFSPropertiesConfiguration(fs.getConf(), new Path(basePath + "/" + PROPS_FILENAME_TEST_SOURCE)).getProps();
    props.put("ssl.trustore.location", "SSL SECRET KEY");
    props.put("sasl.jaas.config", "SASL SECRET KEY");
    props.put("auth.credentials", "AUTH CREDENTIALS");
    props.put("auth.user.info", "AUTH USER INFO");

    String truncatedKeys = HoodieDeltaStreamer.toSortedTruncatedString(props);
    assertFalse(truncatedKeys.contains("SSL SECRET KEY"));
    assertFalse(truncatedKeys.contains("SASL SECRET KEY"));
    assertFalse(truncatedKeys.contains("AUTH CREDENTIALS"));
    assertFalse(truncatedKeys.contains("AUTH USER INFO"));
    assertTrue(truncatedKeys.contains("SENSITIVE_INFO_MASKED"));
  }

  void testDeltaStreamerWithSpecifiedOperation(final String tableBasePath, WriteOperationType operationType, HoodieRecordType recordType) throws Exception {
    // Initial insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    addRecordMerger(recordType, cfg.configs);
    new HoodieDeltaStreamer(cfg, jsc).sync();
    assertRecordCount(1000, tableBasePath, sqlContext);
    assertDistanceCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // Collect the fileIds before running HoodieDeltaStreamer
    Set<String> beforeFileIDs = getAllFileIDsInTable(tableBasePath, Option.empty());

    // setting the operationType
    cfg.operation = operationType;
    // No new data => no commits.
    cfg.sourceLimit = 0;
    new HoodieDeltaStreamer(cfg, jsc).sync();

    if (operationType == WriteOperationType.INSERT_OVERWRITE) {
      assertRecordCount(1000, tableBasePath, sqlContext);
      assertDistanceCount(1000, tableBasePath, sqlContext);
      TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);
    } else if (operationType == WriteOperationType.INSERT_OVERWRITE_TABLE) {
      HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setConf(jsc.hadoopConfiguration()).setBasePath(tableBasePath).build();
      final HoodieTableFileSystemView fsView = new HoodieTableFileSystemView(metaClient, metaClient.getCommitsAndCompactionTimeline());
      assertEquals(0, fsView.getLatestFileSlices("").count());
      TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

      // Since the table has been overwritten all fileIDs before should have been replaced
      Set<String> afterFileIDs = getAllFileIDsInTable(tableBasePath, Option.empty());
      assertTrue(afterFileIDs.isEmpty());
    }

    cfg.sourceLimit = 1000;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    assertRecordCount(950, tableBasePath, sqlContext);
    assertDistanceCount(950, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00001", tableBasePath, fs, 2);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testFetchingCheckpointFromPreviousCommits() throws IOException {
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(basePath + "/testFetchPreviousCheckpoint", WriteOperationType.BULK_INSERT);

    TypedProperties properties = new TypedProperties();
    properties.setProperty("hoodie.datasource.write.recordkey.field", "key");
    properties.setProperty("hoodie.datasource.write.partitionpath.field", "pp");
    TestStreamSync testDeltaSync = new TestStreamSync(cfg, sparkSession, null, properties,
        jsc, fs, jsc.hadoopConfiguration(), null);

    properties.put(HoodieTableConfig.NAME.key(), "sample_tbl");
    HoodieTableMetaClient metaClient = HoodieTestUtils.init(jsc.hadoopConfiguration(), basePath, HoodieTableType.COPY_ON_WRITE, properties);

    Map<String, String> extraMetadata = new HashMap<>();
    extraMetadata.put(HoodieWriteConfig.DELTASTREAMER_CHECKPOINT_KEY, "abc");
    addCommitToTimeline(metaClient, extraMetadata);
    metaClient.reloadActiveTimeline();
    assertEquals(testDeltaSync.getLatestCommitMetadataWithValidCheckpointInfo(metaClient.getActiveTimeline()
        .getCommitsTimeline()).get().getMetadata(CHECKPOINT_KEY), "abc");

    extraMetadata.put(HoodieWriteConfig.DELTASTREAMER_CHECKPOINT_KEY, "def");
    addCommitToTimeline(metaClient, extraMetadata);
    metaClient.reloadActiveTimeline();
    assertEquals(testDeltaSync.getLatestCommitMetadataWithValidCheckpointInfo(metaClient.getActiveTimeline()
        .getCommitsTimeline()).get().getMetadata(CHECKPOINT_KEY), "def");

    // add a replace commit which does not have CHECKPOINT_KEY. Deltastreamer should be able to go back and pick the right checkpoint.
    addReplaceCommitToTimeline(metaClient, Collections.emptyMap());
    metaClient.reloadActiveTimeline();
    assertEquals(testDeltaSync.getLatestCommitMetadataWithValidCheckpointInfo(metaClient.getActiveTimeline()
        .getCommitsTimeline()).get().getMetadata(CHECKPOINT_KEY), "def");
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testDropPartitionColumns(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/test_drop_partition_columns" + testNum++;
    // ingest data with dropping partition columns enabled
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.configs.add(String.format("%s=%s", HoodieTableConfig.DROP_PARTITION_COLUMNS.key(), "true"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();
    // assert ingest successful
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);

    TableSchemaResolver tableSchemaResolver = new TableSchemaResolver(
        HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(fs.getConf()).build());
    // get schema from data file written in the latest commit
    Schema tableSchema = tableSchemaResolver.getTableAvroSchemaFromDataFile();
    assertNotNull(tableSchema);

    List<String> tableFields = tableSchema.getFields().stream().map(Schema.Field::name).collect(Collectors.toList());
    // now assert that the partition column is not in the target schema
    assertFalse(tableFields.contains("partition_path"));
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testForceEmptyMetaSync() throws Exception {
    String tableBasePath = basePath + "/test_force_empty_meta_sync";

    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    cfg.sourceLimit = 0;
    cfg.allowCommitOnNoCheckpointChange = true;
    cfg.enableMetaSync = true;
    cfg.forceEmptyMetaSync = true;

    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    assertRecordCount(0, tableBasePath, sqlContext);

    // make sure hive table is present
    HiveSyncConfig hiveSyncConfig = getHiveSyncConfig(tableBasePath, "hive_trips");
    hiveSyncConfig.setHadoopConf(hiveServer.getHiveConf());
    HoodieHiveSyncClient hiveClient = new HoodieHiveSyncClient(hiveSyncConfig);
    final String tableName = hiveSyncConfig.getString(META_SYNC_TABLE_NAME);
    assertTrue(hiveClient.tableExists(tableName), "Table " + tableName + " should exist");
  }

  @Test
  public void testResumeCheckpointAfterChangingCOW2MOR() throws Exception {
    String tableBasePath = basePath + "/test_resume_checkpoint_after_changing_cow_to_mor";
    // default table type is COW
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    new HoodieDeltaStreamer(cfg, jsc).sync();
    assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);

    // change cow to mor
    HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder()
        .setConf(new Configuration(fs.getConf()))
        .setBasePath(cfg.targetBasePath)
        .setLoadActiveTimelineOnLoad(false)
        .build();
    Properties hoodieProps = new Properties();
    hoodieProps.load(fs.open(new Path(cfg.targetBasePath + "/.hoodie/hoodie.properties")));
    LOG.info("old props: {}", hoodieProps);
    hoodieProps.put("hoodie.table.type", HoodieTableType.MERGE_ON_READ.name());
    LOG.info("new props: {}", hoodieProps);
    Path metaPathDir = new Path(metaClient.getBasePathV2(), METAFOLDER_NAME);
    HoodieTableConfig.create(metaClient.getFs(), metaPathDir, hoodieProps);

    // continue deltastreamer
    cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    cfg.tableType = HoodieTableType.MERGE_ON_READ.name();
    new HoodieDeltaStreamer(cfg, jsc).sync();
    // out of 1000 new records, 500 are inserts, 450 are updates and 50 are deletes.
    assertRecordCount(1450, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00001", tableBasePath, fs, 2);
    List<Row> counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1450, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);
    // currently there should be 1 deltacommits now
    TestHelpers.assertAtleastNDeltaCommits(1, tableBasePath, fs);

    // test the table type is already mor
    new HoodieDeltaStreamer(cfg, jsc).sync();
    // out of 1000 new records, 500 are inserts, 450 are updates and 50 are deletes.
    // total records should be 1900 now
    assertRecordCount(1900, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00002", tableBasePath, fs, 3);
    counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1900, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);
    // currently there should be 2 deltacommits now
    TestHelpers.assertAtleastNDeltaCommits(2, tableBasePath, fs);

    // clean up
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testResumeCheckpointAfterChangingMOR2COW() throws Exception {
    String tableBasePath = basePath + "/test_resume_checkpoint_after_changing_mor_to_cow";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    // change table type to MOR
    cfg.tableType = HoodieTableType.MERGE_ON_READ.name();
    new HoodieDeltaStreamer(cfg, jsc).sync();
    assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);

    // sync once, make one deltacommit and do a full compaction
    cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    cfg.tableType = HoodieTableType.MERGE_ON_READ.name();
    cfg.configs.add("hoodie.compaction.strategy=org.apache.hudi.table.action.compact.strategy.UnBoundedCompactionStrategy");
    cfg.configs.add("hoodie.compact.inline.max.delta.commits=1");
    new HoodieDeltaStreamer(cfg, jsc).sync();
    // out of 1000 new records, 500 are inserts, 450 are updates and 50 are deletes.
    assertRecordCount(1450, tableBasePath, sqlContext);
    // totalCommits: 1 deltacommit(bulk_insert) + 1 deltacommit(upsert) + 1 commit(compaction)
    // there is no checkpoint in the compacted commit metadata, the latest checkpoint 00001 is in the upsert deltacommit
    TestHelpers.assertCommitMetadata(null, tableBasePath, fs, 3);
    List<Row> counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1450, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());
    TestHelpers.assertAtLeastNCommits(3, tableBasePath, fs);
    // currently there should be 2 deltacommits now
    TestHelpers.assertAtleastNDeltaCommits(2, tableBasePath, fs);

    // change mor to cow
    HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder()
        .setConf(new Configuration(fs.getConf()))
        .setBasePath(cfg.targetBasePath)
        .setLoadActiveTimelineOnLoad(false)
        .build();
    Properties hoodieProps = new Properties();
    hoodieProps.load(fs.open(new Path(cfg.targetBasePath + "/.hoodie/hoodie.properties")));
    LOG.info("old props: " + hoodieProps);
    hoodieProps.put("hoodie.table.type", HoodieTableType.COPY_ON_WRITE.name());
    LOG.info("new props: " + hoodieProps);
    Path metaPathDir = new Path(metaClient.getBasePathV2(), ".hoodie");
    HoodieTableConfig.create(metaClient.getFs(), metaPathDir, hoodieProps);

    // continue deltastreamer
    cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    new HoodieDeltaStreamer(cfg, jsc).sync();
    // out of 1000 new records, 500 are inserts, 450 are updates and 50 are deletes.
    assertRecordCount(1900, tableBasePath, sqlContext);
    // the checkpoint now should be 00002
    TestHelpers.assertCommitMetadata("00002", tableBasePath, fs, 4);
    counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1900, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());
    TestHelpers.assertAtLeastNCommits(4, tableBasePath, fs);

    // test the table type is already cow
    new HoodieDeltaStreamer(cfg, jsc).sync();
    // out of 1000 new records, 500 are inserts, 450 are updates and 50 are deletes.
    // total records should be 2350 now
    assertRecordCount(2350, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00003", tableBasePath, fs, 5);
    counts = countsPerCommit(tableBasePath, sqlContext);
    assertEquals(2350, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());
    TestHelpers.assertAtLeastNCommits(5, tableBasePath, fs);

    // clean up
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testAutoGenerateRecordKeys() throws Exception {
    boolean useSchemaProvider = false;
    List<String> transformerClassNames = null;
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfs" + testNum;
    int parquetRecordsCount = 100;
    boolean hasTransformer = transformerClassNames != null && !transformerClassNames.isEmpty();
    prepareParquetDFSFiles(parquetRecordsCount, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, false, null, null);
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "partition_path", "");

    String tableBasePath = basePath + "/test_parquet_table" + testNum;
    HoodieDeltaStreamer.Config config = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
        transformerClassNames, PROPS_FILENAME_TEST_PARQUET, false,
        useSchemaProvider, 100000, false, null, null, "timestamp", null);
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(config, jsc);
    deltaStreamer.sync();
    assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);

    prepareParquetDFSFiles(200, PARQUET_SOURCE_ROOT, "2.parquet", false, null, null);
    deltaStreamer.sync();
    assertRecordCount(parquetRecordsCount + 200, tableBasePath, sqlContext);
    testNum++;
  }

  @ParameterizedTest
  @CsvSource(value = {"COPY_ON_WRITE, AVRO", "MERGE_ON_READ, AVRO",
      "COPY_ON_WRITE, SPARK", "MERGE_ON_READ, SPARK"})
  public void testConfigurationHotUpdate(HoodieTableType tableType, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + String.format("/configurationHotUpdate_%s_%s", tableType.name(), recordType.name());

    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = tableType.name();
    cfg.configHotUpdateStrategyClass = MockConfigurationHotUpdateStrategy.class.getName();
    long upsertParallelism = 200;
    cfg.configs.add(String.format("%s=%s", UPSERT_PARALLELISM_VALUE.key(), upsertParallelism));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);
      // make sure the UPSERT_PARALLELISM_VALUE already changed (hot updated)
      Assertions.assertTrue(((HoodieStreamer.StreamSyncService) ds.getIngestionService()).getProps().getLong(UPSERT_PARALLELISM_VALUE.key()) > upsertParallelism);
      return true;
    });
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  private Set<String> getAllFileIDsInTable(String tableBasePath, Option<String> partition) {
    HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setConf(jsc.hadoopConfiguration()).setBasePath(tableBasePath).build();
    final HoodieTableFileSystemView fsView = new HoodieTableFileSystemView(metaClient, metaClient.getCommitsAndCompactionTimeline());
    Stream<HoodieBaseFile> baseFileStream = partition.isPresent() ? fsView.getLatestBaseFiles(partition.get()) : fsView.getLatestBaseFiles();
    return baseFileStream.map(HoodieBaseFile::getFileId).collect(Collectors.toSet());
  }

  class TestStreamSync extends DeltaSync {

    public TestStreamSync(HoodieDeltaStreamer.Config cfg, SparkSession sparkSession, SchemaProvider schemaProvider, TypedProperties props,
                          JavaSparkContext jssc, FileSystem fs, Configuration conf,
                          Function<SparkRDDWriteClient, Boolean> onInitializingHoodieWriteClient) throws IOException {
      super(cfg, sparkSession, schemaProvider, props, jssc, fs, conf, onInitializingHoodieWriteClient);
    }

    public Option<HoodieCommitMetadata> getLatestCommitMetadataWithValidCheckpointInfo(HoodieTimeline timeline) throws IOException {
      return super.getLatestCommitMetadataWithValidCheckpointInfo(timeline);
    }
  }

  /**
   * UDF to calculate Haversine distance.
   */
  public static class DistanceUDF implements UDF4<Double, Double, Double, Double, Double> {

    /**
     * Returns some random number as distance between the points.
     *
     * @param lat1 Latitude of source
     * @param lat2 Latitude of destination
     * @param lon1 Longitude of source
     * @param lon2 Longitude of destination
     */
    @Override
    public Double call(Double lat1, Double lat2, Double lon1, Double lon2) {
      return RANDOM.nextDouble();
    }
  }

  /**
   * Adds a new field "haversine_distance" to the row.
   */
  public static class TripsWithDistanceTransformer implements Transformer {

    @Override
    public Dataset<Row> apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                              TypedProperties properties) {
      rowDataset.sqlContext().udf().register("distance_udf", new DistanceUDF(), DataTypes.DoubleType);
      return rowDataset.withColumn("haversine_distance", functions.callUDF("distance_udf", functions.col("begin_lat"),
          functions.col("end_lat"), functions.col("begin_lon"), functions.col("end_lat")));
    }
  }

  public static class TestGenerator extends SimpleKeyGenerator {

    public TestGenerator(TypedProperties props) {
      super(props);
    }
  }

  public static class DummyAvroPayload extends OverwriteWithLatestAvroPayload {

    public DummyAvroPayload(GenericRecord gr, Comparable orderingVal) {
      super(gr, orderingVal);
    }
  }

  /**
   * Return empty table.
   */
  public static class DropAllTransformer implements Transformer {

    @Override
    public Dataset apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                         TypedProperties properties) {
      System.out.println("DropAllTransformer called !!");
      return sparkSession.createDataFrame(jsc.emptyRDD(), rowDataset.schema());
    }
  }

  public static class TestIdentityTransformer implements Transformer {

    @Override
    public Dataset<Row> apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                              TypedProperties properties) {
      return rowDataset;
    }
  }

  public static class TestSpecificPartitionTransformer implements Transformer {

    @Override
    public Dataset<Row> apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                              TypedProperties properties) {
      Dataset<Row> toReturn = rowDataset.filter("partition_path == '" + HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH + "'");
      return toReturn;
    }
  }

  /**
   * Add new field evoluted_optional_union_field with value of the field rider.
   */
  public static class TripsWithEvolvedOptionalFieldTransformer implements Transformer {

    @Override
    public Dataset<Row> apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                              TypedProperties properties) {
      return rowDataset.withColumn("evoluted_optional_union_field", functions.col("rider"));
    }
  }

  /**
   * {@link FilebasedSchemaProvider} to be used in tests where target schema is null.
   */
  public static class TestFileBasedSchemaProviderNullTargetSchema extends FilebasedSchemaProvider {

    public TestFileBasedSchemaProviderNullTargetSchema(TypedProperties props, JavaSparkContext jssc) {
      super(props, jssc);
    }

    @Override
    public Schema getTargetSchema() {
      return null;
    }
  }

  private static Stream<Arguments> testORCDFSSource() {
    // arg1 boolean useSchemaProvider, arg2 List<String> transformerClassNames
    return Stream.of(
        arguments(false, null),
        arguments(true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()))
    );
  }

  public static class NullValueSchemaProvider extends SchemaProvider {

    public NullValueSchemaProvider(TypedProperties props) {
      super(props);
    }

    public NullValueSchemaProvider(TypedProperties props, JavaSparkContext jssc) {
      super(props, jssc);
    }

    @Override
    public Schema getSourceSchema() {
      return null;
    }
  }
}

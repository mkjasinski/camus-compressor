package pl.allegro.tech.hadoop.compressor;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.gson.Gson;
import com.palantir.curatortestrule.SharedZooKeeperRule;
import com.palantir.curatortestrule.ZooKeeperRule;
import kafka.utils.ZkUtils;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkConnection;
import org.apache.commons.io.IOUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.junit.*;
import org.junit.runners.MethodSorters;
import pl.allegro.tech.hadoop.compressor.schema.SchemaRegistrySchemaRepository;
import pl.allegro.tech.hadoop.compressor.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.Assert.assertEquals;

@FixMethodOrder(MethodSorters.DEFAULT)
public class CompressorIntegrationTest {

    private static final String SCHEMAREPO_HOST = "http://localhost:2877";
    private static final int SCHEMAREPO_PORT = 2877;

    private static final long UNCOMPRESSED_JSON_SIZE = 34L;
    private static final long COMPRESSED_JSON_SIZE = 105L;
    private static final long FIRST_AVRO_SIZE = 490L;
    private static final long SECOND_AVRO_SIZE = 494L;
    private static final long COMPRESSED_AVRO_SIZE = 527L;
    private static final String TOPIC_PAYLOAD = "{\"version\":1,\"partitions\":{\"0\":[114,224]}}";

    private static File baseDir;
    private static FileSystem fileSystem;
    private static MiniDFSCluster hdfsCluster;
    private String todayPath;
    private String pastDayPath;

    @ClassRule
    public static ZooKeeperRule sharedZookeeper = new SharedZooKeeperRule();

    @Rule
    public WireMockRule wireMock = new WireMockRule(SCHEMAREPO_PORT);
    private static CuratorFramework zookeeper;

    @BeforeClass
    public static void setUpZookeeper() throws Exception {
        zookeeper = sharedZookeeper.getClient();
        final ZkClient zkClient = new ZkClient(zookeeper.getZookeeperClient().getCurrentConnectionString());
        final ZkUtils zkUtils = new ZkUtils(zkClient,
                new ZkConnection(zookeeper.getZookeeperClient().getCurrentConnectionString()), false);
        final List<ACL> acls = Collections.singletonList(new ACL(ZooDefs.Perms.ALL, new Id("world", "anyone")));
        zkUtils.updatePersistentPath("/brokers/topics/topic1_avro", TOPIC_PAYLOAD, acls);
        zkUtils.updatePersistentPath("/brokers/topics/topic2_avro", TOPIC_PAYLOAD, acls);
        zkUtils.updatePersistentPath("/brokers/topics/topic1", TOPIC_PAYLOAD, acls);
        zkUtils.updatePersistentPath("/brokers/topics/topic2", TOPIC_PAYLOAD, acls);
    }

    @BeforeClass
    public static void setUp() throws Exception {
        System.setProperty("spark.master", "local");
        System.setProperty("spark.driver.allowMultipleContexts", "true");
        System.setProperty("spark.executor.instances", "1");
        System.setProperty("spark.compressor.avro.schema.repository.class",
                "pl.allegro.tech.hadoop.compressor.schema.SchemaRegistrySchemaRepository");
        System.setProperty("spark.compressor.processing.topic-name-retriever.class",
                "pl.allegro.tech.hadoop.compressor.schema.KafkaTopicNameRetriever");
        System.setProperty("spark.compressor.processing.mode", "all");
        System.setProperty("spark.compressor.output.compression", "none");
        System.setProperty("spark.compressor.avro.schema.repository.url", SCHEMAREPO_HOST);
        System.setProperty("spark.compressor.processing.delay", "1");
        System.setProperty("spark.compressor.processing.mode.all.excludes", "base,history,integration");
        System.setProperty("spark.compressor.processing.mode.topic.pattern", "daily,hourly");
        System.setProperty("spark.compressor.processing.delay", "1");
        System.setProperty("spark.compressor.processing.calculate.counts", "true");
        System.setProperty("spark.compressor.zookeeper.paths",
                zookeeper.getZookeeperClient().getCurrentConnectionString()+","+zookeeper.getZookeeperClient().getCurrentConnectionString());
        baseDir = Files.createTempDirectory("hdfs").toFile();
        FileUtil.fullyDelete(baseDir);

        Configuration conf = new Configuration();
        conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, baseDir.getAbsolutePath());
        MiniDFSCluster.Builder builder = new MiniDFSCluster.Builder(conf);
        hdfsCluster = builder.build();

        Iterator<Entry<String, String>> i = conf.iterator();
        while (i.hasNext()) {
            Entry<String, String> data = i.next();
            System.setProperty("spark.hadoop." + data.getKey(), data.getValue());
        }
        fileSystem = FileSystemUtils.getFileSystem(conf);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        hdfsCluster.shutdown();
        FileUtil.fullyDelete(baseDir);
    }

    @Test
    public void shouldPerformJsonCompression() throws Exception {
        // given
        System.setProperty("spark.compressor.input.format", "json");
        System.setProperty("spark.compressor.input.path", "/camus_main_dir");
        prepareData();

        // when
        Compressor.main();

        // then
        checkUncompressed("/camus_main_dir/topic1/daily/" + todayPath + "/file1");
        checkUncompressed("/camus_main_dir/topic1/daily/" + todayPath + "/file2");
        checkUncompressed("/camus_main_dir/topic1/daily/" + todayPath + "/file3");
        checkCompressed("/camus_main_dir/topic1/daily/" + pastDayPath + "/*");

        checkUncompressed("/camus_main_dir/topic2/hourly/" + todayPath + "/12/file1");
        checkUncompressed("/camus_main_dir/topic2/hourly/" + todayPath + "/12/file2");
        checkUncompressed("/camus_main_dir/topic2/hourly/" + todayPath + "/12/file3");
        checkCompressed("/camus_main_dir/topic2/hourly/" + pastDayPath + "/12/*");
    }

    @Test
    public void shouldPerformAvroCompression() throws Exception {
        // given
        System.setProperty("spark.compressor.input.format", "avro");
        System.setProperty("spark.compressor.input.path", "/camus_main_avro_dir");
        prepareAvroData();
        final String schemaString = IOUtils.toString(getClass().getClassLoader().getResourceAsStream("twitter.avsc"));
        stubSchemaRepo(schemaString, "topic1");
        stubSchemaRepo(schemaString, "topic2");

        // when
        Compressor.main();

        // then
        checkUncompressed(FIRST_AVRO_SIZE, "/camus_main_avro_dir/topic1/daily/" + todayPath + "/file1.avro");
        checkUncompressed(SECOND_AVRO_SIZE, "/camus_main_avro_dir/topic1/daily/" + todayPath + "/file2.avro");
        checkCompressed(COMPRESSED_AVRO_SIZE, "/camus_main_avro_dir/topic1/daily/" + pastDayPath + "/*");

        checkUncompressed(FIRST_AVRO_SIZE, "/camus_main_avro_dir/topic2/hourly/" + todayPath + "/12/file1.avro");
        checkUncompressed(SECOND_AVRO_SIZE, "/camus_main_avro_dir/topic2/hourly/" + todayPath + "/12/file2.avro");
        checkCompressed(COMPRESSED_AVRO_SIZE, "/camus_main_avro_dir/topic2/hourly/" + pastDayPath + "/12/*");
    }

    private void stubSchemaRepo(String schema, String topicName) throws Exception {
        final SchemaRegistrySchemaRepository.SchemaEntry schemaEntry = new SchemaRegistrySchemaRepository.SchemaEntry();
        schemaEntry.setSchema(schema);
        final String schemaString = new Gson().toJson(schemaEntry);

        wireMock.stubFor(get(urlPathEqualTo("/subjects/" + topicName + "/versions/latest"))
                .willReturn(aResponse()
                        .withBody(schemaString)
                        .withStatus(200)));
    }

    private void prepareData() throws Exception {
        fileSystem.mkdirs(new Path("/camus_main_dir"));

        todayPath = new SimpleDateFormat("yyyy/MM/dd").format(new Date());
        pastDayPath = "2015/01/01";

        fillWithData("/camus_main_dir/topic1/daily/"  + todayPath);
        fillWithData("/camus_main_dir/topic1/daily/" + pastDayPath);
        fillWithData("/camus_main_dir/topic2/hourly/" + todayPath + "/12");
        fillWithData("/camus_main_dir/topic2/hourly/" + pastDayPath + "/12");
    }

    private void prepareAvroData() throws Exception {
        fileSystem.mkdirs(new Path("/camus_main_avro_dir"));

        todayPath = new SimpleDateFormat("yyyy/MM/dd").format(new Date());
        pastDayPath = "2015/01/01";

        fillWithAvroData("/camus_main_avro_dir/topic1/daily/" + todayPath);
        fillWithAvroData("/camus_main_avro_dir/topic1/daily/" + pastDayPath);
        fillWithAvroData("/camus_main_avro_dir/topic2/hourly/" + todayPath + "/12");
        fillWithAvroData("/camus_main_avro_dir/topic2/hourly/" + pastDayPath + "/12");
    }

    private void fillWithData(String path) throws Exception {
        fileSystem.mkdirs(new Path(path));
        putSampleData(path + "/file1");
        putSampleData(path + "/file2");
        putSampleData(path + "/file3");
    }

    private void fillWithAvroData(String path) throws Exception {
        fileSystem.mkdirs(new Path(path));
        putAvroFile(path + "/file1.avro", "twitter-1.avro");
        putAvroFile(path + "/file2.avro", "twitter-2.avro");
    }

    private void putAvroFile(String path, String localPath) throws IOException {
        final FSDataOutputStream outputStream = fileSystem.create(new Path(path));
        outputStream.write(IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream(localPath)));
        outputStream.close();
    }

    private void putSampleData(String path) throws Exception {
        FSDataOutputStream os = fileSystem.create(new Path(path));
        os.writeChars("line1\nline2\nline3");
        os.close();
    }

    private void checkCompressed(String path) throws Exception {
        checkCompressed(COMPRESSED_JSON_SIZE, path);
    }

    private void checkCompressed(long size, String path) throws Exception {
        assertEquals(size, fileSystem.globStatus(new Path(path))[0].getLen());
    }

    private void checkUncompressed(String path) throws Exception {
        checkUncompressed(UNCOMPRESSED_JSON_SIZE, path);
    }

    private void checkUncompressed(long size, String path) throws Exception {
        assertEquals(size, fileSystem.globStatus(new Path(path))[0].getLen());
    }
}

package com.jd.blockchain.kvdb.server;

import com.jd.binaryproto.BinaryProtocol;
import com.jd.blockchain.kvdb.protocol.Constants;
import com.jd.blockchain.kvdb.protocol.client.ClientConfig;
import com.jd.blockchain.kvdb.protocol.client.NettyClient;
import com.jd.blockchain.kvdb.protocol.exception.KVDBException;
import com.jd.blockchain.kvdb.protocol.proto.DatabaseClusterInfo;
import com.jd.blockchain.kvdb.protocol.proto.Response;
import com.jd.blockchain.kvdb.protocol.proto.impl.KVDBMessage;
import com.jd.blockchain.kvdb.server.config.KVDBConfig;
import com.jd.blockchain.kvdb.server.config.ServerConfig;

import utils.Bytes;
import utils.io.FileUtils;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;

public class ServerTest {

    @Before
    public void setUp() throws Exception {
        System.setProperty("logging.path", "logs");
        clearTestDBs();
    }

    @After
    public void tearDown() throws Exception {
        clearTestDBs();
    }

    private void clearTestDBs() throws Exception {
        FileUtils.deletePath(new File(new ServerConfig(this.getClass().getResource("/server/single").getFile()).getKvdbConfig().getDbsRootdir()), true);
        FileUtils.deletePath(new File(new ServerConfig(this.getClass().getResource("/server/cluster/1").getFile()).getKvdbConfig().getDbsRootdir()), true);
        FileUtils.deletePath(new File(new ServerConfig(this.getClass().getResource("/server/cluster/2").getFile()).getKvdbConfig().getDbsRootdir()), true);
    }

    @Test
    public void testSingle() throws Exception {
        KVDBServerContext context = new KVDBServerContext(new ServerConfig(this.getClass().getResource("/server/single").getFile()));
        KVDBServer server = new KVDBServer(context);
        server.start();

        CountDownLatch reqCdl = new CountDownLatch(1);
        KVDBConfig config = context.getConfig().getKvdbConfig();
        NettyClient client = new NettyClient(new ClientConfig("localhost", config.getPort(), ""), () -> reqCdl.countDown());
        reqCdl.await();
        Response response = client.send(KVDBMessage.use("test1"));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        DatabaseClusterInfo clusterInfo = BinaryProtocol.decodeAs(response.getResult()[0].toBytes(), DatabaseClusterInfo.class);
        Assert.assertFalse(clusterInfo.isClusterMode());
        for (int i = 0; i < 100; i++) {
            response = client.send(KVDBMessage.put(Bytes.fromInt(i), Bytes.fromInt(i)));
            Assert.assertEquals(Constants.SUCCESS, response.getCode());
        }
        client.send(KVDBMessage.batchBegin());
        for (int i = 0; i < 10000; i++) {
            Assert.assertTrue(client.sendAsync(KVDBMessage.put(Bytes.fromInt(i), Bytes.fromInt(i))));
        }
        client.send(KVDBMessage.batchCommit());
        server.stop();
    }

    @Test
    public void testCluster() throws Exception {
        KVDBServerContext context1 = new KVDBServerContext(new ServerConfig(this.getClass().getResource("/server/cluster/1").getFile()));
        KVDBServer server1 = new KVDBServer(context1);
        CountDownLatch cdl = new CountDownLatch(2);
        new Thread(() -> {
            try {
                server1.start();
            } catch (KVDBException e) {
                e.printStackTrace();
            }
            cdl.countDown();

        }).start();

        KVDBServerContext context2 = new KVDBServerContext(new ServerConfig(this.getClass().getResource("/server/cluster/2").getFile()));
        KVDBServer server2 = new KVDBServer(context2);
        new Thread(() -> {
            try {
                server2.start();
            } catch (KVDBException e) {
                e.printStackTrace();
            }
            cdl.countDown();
        }).start();

        cdl.await();

        CountDownLatch reqCdl = new CountDownLatch(1);
        KVDBConfig config = context1.getConfig().getKvdbConfig();
        NettyClient client = new NettyClient(new ClientConfig("localhost", config.getPort(), ""), () -> reqCdl.countDown());
        reqCdl.await();
        Response response = client.send(KVDBMessage.use("test1"));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        DatabaseClusterInfo clusterInfo = BinaryProtocol.decodeAs(response.getResult()[0].toBytes(), DatabaseClusterInfo.class);
        Assert.assertTrue(clusterInfo.isClusterMode());
        Assert.assertEquals("test1", clusterInfo.getClusterItem().getName());
        Assert.assertEquals(2, clusterInfo.getClusterItem().getURLs().length);


        server1.stop();
        server2.stop();
    }
}

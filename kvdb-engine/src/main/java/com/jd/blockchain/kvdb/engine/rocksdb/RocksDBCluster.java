package com.jd.blockchain.kvdb.engine.rocksdb;

import com.jd.blockchain.kvdb.engine.Config;
import com.jd.blockchain.kvdb.engine.KVDBInstance;
import com.jd.blockchain.kvdb.protocol.proto.wal.Entity;
import com.jd.blockchain.kvdb.protocol.proto.wal.EntityCoder;
import com.jd.blockchain.kvdb.protocol.proto.wal.KV;
import com.jd.blockchain.kvdb.protocol.proto.wal.KVItem;
import com.jd.blockchain.kvdb.protocol.proto.wal.WalEntity;
import com.jd.blockchain.utils.Bytes;
import com.jd.blockchain.utils.io.FileUtils;
import com.jd.blockchain.wal.FileLogger;
import com.jd.blockchain.wal.WalConfig;
import com.jd.blockchain.wal.WalIterator;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RocksDBCluster extends KVDBInstance {

    public static final Logger LOGGER = LoggerFactory.getLogger(RocksDBCluster.class);

    private Partitioner partitioner;

    private String rootPath;

    private FileLogger<Entity> wal;

    private RocksDBProxy[] dbPartitions;

    private ExecutorService executor;

    private boolean checkpointDisable = false;

    private RocksDBCluster(String rootPath, RocksDBProxy[] dbPartitions, ExecutorService executor) {
        this(rootPath, dbPartitions, executor, null);
    }

    private RocksDBCluster(String rootPath, RocksDBProxy[] dbPartitions, ExecutorService executor, FileLogger<Entity> wal) {
        this.rootPath = rootPath;
        this.dbPartitions = dbPartitions;
        this.executor = executor;
        this.partitioner = new SimpleMurmur3HashPartitioner(dbPartitions.length);
        this.wal = wal;
    }

    public static RocksDBCluster open(String path, int partitions) throws RocksDBException {
        return open(path, partitions, null);
    }

    public static RocksDBCluster open(String path, int partitions, Config config) throws RocksDBException {
        LOGGER.info("db [{}] wal config: {}", path, null != config ? config.toString() : "null");
        String rootPath = FileUtils.getFullPath(path);
        RocksDBProxy[] dbPartitions = new RocksDBProxy[partitions];
        for (int i = 0; i < partitions; i++) {
            String partitionPath = rootPath + File.separator + "parti-" + i;
            FileUtils.makeDirectory(partitionPath);
            dbPartitions[i] = RocksDBProxy.open(partitionPath);
        }

        ExecutorService executor = Executors.newFixedThreadPool(dbPartitions.length);

        try {
            RocksDBCluster instance;
            if (null == config || config.isWalDisable()) {
                instance = new RocksDBCluster(rootPath, dbPartitions, executor);
            } else {
                WalConfig walConfig = new WalConfig(config.getWalpath(), config.getWalFlush(), true);
                instance = new RocksDBCluster(rootPath, dbPartitions, executor, new FileLogger<>(walConfig, EntityCoder.getInstance()));
            }

            instance.redo();

            return instance;
        } catch (IOException e) {
            throw new RocksDBException(e.toString());
        }
    }

    // 检查wal日志进行数据一致性检查
    private void redo() throws IOException, RocksDBException {
        if (wal != null) {
            LOGGER.debug("redo wal...");

            WalIterator<Entity> iterator = wal.forwardIterator();
            while (iterator.hasNext()) {
                Entity e = iterator.next();
                WriteBatch[] batchs = new WriteBatch[dbPartitions.length];
                for (KV kv : e.getKVs()) {
                    int index = partitioner.partition(kv.getKey());
                    if (null == batchs[index]) {
                        batchs[index] = new WriteBatch();
                    }
                    batchs[index].put(kv.getKey(), kv.getValue());
                }
                for (int i = 0; i < dbPartitions.length; i++) {
                    if (batchs[i] != null && batchs[i].getDataSize() > 0) {
                        dbPartitions[i].write(batchs[i]);
                    }
                }
            }

            // 清空WAL
            wal.clear();
            LOGGER.info("redo wal complete");
        }
    }

    @Override
    public synchronized void set(byte[] key, byte[] value) throws RocksDBException {
        if (null != wal) {
            try {
                wal.append(WalEntity.newPutEntity(new KVItem(key, value)));
            } catch (IOException e) {
                LOGGER.error("DBCluster set, wal append error! --" + e.getMessage(), e);
                throw new RocksDBException(e.toString());
            }
        }
        try {
            int pid = partitioner.partition(key);
            dbPartitions[pid].set(key, value);
        } catch (Exception e) {
            checkpointDisable = true;
            LOGGER.error("DBCluster set error! --" + e.getMessage(), e);
            throw e;
        }
        if (null != wal && !checkpointDisable) {
            try {
                wal.checkpoint();
            } catch (IOException e) {
                LOGGER.error("DBCluster set, wal checkpoint error! --" + e.getMessage(), e);
            }
        }

    }

    @Override
    public byte[] get(byte[] key) throws RocksDBException {
        int pid = partitioner.partition(key);
        return dbPartitions[pid].get(key);
    }

    @Override
    public synchronized void close() {
        if (null != wal) {
            try {
                wal.close();
            } catch (IOException e) {
                LOGGER.error("Error occurred while closing wal[" + rootPath + "]", e);
            }
        }
        if (dbPartitions != null) {
            try {
                executor.shutdown();
            } catch (Exception e) {
                LOGGER.error("Error occurred while closing rocksdb cluster[" + rootPath + "]", e);
            }

            for (int i = 0; i < dbPartitions.length; i++) {
                dbPartitions[i].close();
            }

            dbPartitions = null;
        }
    }

    @Override
    public synchronized void drop() {
        if (null != wal) {
            try {
                wal.close();
            } catch (IOException e) {
                LOGGER.error("Error occurred while closing wal[" + rootPath + "]", e);
            }
        }
        if (dbPartitions != null) {
            RocksDBProxy[] dbs = dbPartitions;

            close();

            for (int i = 0; i < dbs.length; i++) {
                dbs[i].drop();
            }

            try {
                FileUtils.deleteFile(rootPath);
            } catch (Exception e) {
                LOGGER.error("Error occurred while dropping rocksdb cluster[" + rootPath + "]", e);
            }
        }

    }

    @Override
    public synchronized void batchSet(Map<Bytes, byte[]> kvs) throws RocksDBException {
        if (null == kvs || kvs.size() == 0) {
            return;
        }
        Map<Bytes, byte[]>[] batches = new HashMap[dbPartitions.length];
        KVItem[] walkvs = new KVItem[kvs.size()];
        int j = 0;
        for (Map.Entry<Bytes, byte[]> entry : kvs.entrySet()) {
            int index = partitioner.partition(entry.getKey().toBytes());
            if (null == batches[index]) {
                batches[index] = new HashMap<>();
            }
            batches[index].put(entry.getKey(), entry.getValue());
            walkvs[j] = new KVItem(entry.getKey().toBytes(), entry.getValue());
            j++;
        }

        if (null != wal) {
            try {
                wal.append(WalEntity.newPutEntity(walkvs));
            } catch (Exception e) {
                LOGGER.error("DBCluster batch commit, wal append error! --" + e.getMessage(), e);
                throw new RocksDBException(e.toString());
            }
        }
        try {
            CompletionService<Boolean> completionService = new ExecutorCompletionService<>(executor);
            int batchThreads = 0;
            for (int i = 0; i < batches.length; i++) {
                final int index = i;
                if (null != batches[i] && batches[i].size() > 0) {
                    completionService.submit(() -> {
                        try {
                            dbPartitions[index].batchSet(batches[index]);
                            return true;
                        } catch (Exception e) {
                            LOGGER.error("KVWrite batch task error! --" + e.getMessage(), e);
                            return false;
                        }
                    });

                    batchThreads++;
                }
            }
            for (int i = 0; i < batchThreads; i++) {
                if (!completionService.take().get()) {
                    LOGGER.error("Sub thread batch commit error");
                    throw new RocksDBException("Sub thread batch commit error");
                }
            }
        } catch (Exception e) {
            checkpointDisable = true;
            LOGGER.error("DBCluster batch commit error! --" + e.getMessage(), e);
            throw new RocksDBException(e.toString());
        }

        if (null != wal && !checkpointDisable) {
            try {
                wal.checkpoint();
            } catch (IOException e) {
                LOGGER.error("DBCluster batch commit, wal checkpoint error! --" + e.getMessage(), e);
            }
        }
    }
}

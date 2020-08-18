package com.jd.blockchain.kvdb.engine.wal;

public class RedoLogConfig {

    // 日志文件目录
    private String walpath;
    // 是否禁用WAL
    private boolean walDisable;
    // 刷盘机制：<=-1跟随系统，0实时刷盘，>0定时刷盘
    private int walFlush;

    public RedoLogConfig(String path, boolean walDisable, int walFlush) {
        this.walpath = path;
        this.walDisable = walDisable;
        this.walFlush = walFlush;
    }

    public String getWalpath() {
        return walpath;
    }

    public boolean isWalDisable() {
        return walDisable;
    }

    public int getWalFlush() {
        return walFlush;
    }
}

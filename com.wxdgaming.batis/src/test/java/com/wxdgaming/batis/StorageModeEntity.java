package com.wxdgaming.batis;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.model.StorageMode;

import java.util.List;

/**
 * 显式 StorageMode 实体，用于测试存储模式 → 逻辑类型映射
 */
@Table(name = "t_storage_mode")
public class StorageModeEntity {

    @Id
    private long id;

    @Column(storageMode = StorageMode.JSON)
    private List<String> payload;

    @Column(storageMode = StorageMode.LONG)
    private Object counter;

    @Column(storageMode = StorageMode.VARCHAR, length = 64)
    private String code;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public List<String> getPayload() { return payload; }
    public void setPayload(List<String> payload) { this.payload = payload; }
    public Object getCounter() { return counter; }
    public void setCounter(Object counter) { this.counter = counter; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}

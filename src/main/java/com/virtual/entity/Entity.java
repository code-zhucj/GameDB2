package com.virtual.entity;

import com.virtual.exception.GameDBException;
import com.virtual.codec.Codec;
import lombok.Setter;

@com.virtual.api.Entity
public abstract class Entity implements Codec {

    @Setter
    private Entity root;

    public Entity() {
    }

    public Entity(Entity root) {
        this.root = root;
    }

    public void checkValid(Entity root) {
        if (this.root != null && this.root != root) {
            throw new GameDBException("当前对象已被其他表引用"); // "当前对象已被其他表引用"
        }
    }

    public Entity getRoot() {
        return this.root == null ? this : this.root;
    }

}

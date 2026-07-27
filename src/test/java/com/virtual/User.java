package com.virtual;

import com.virtual.api.T;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@T
@Getter
@Setter
public class User extends TableDefine<Integer> {

    private int userId;
    private String name;
    private Bag bag;
    private Map<Integer, Integer> items;

    @Override
    public Integer primaryKey() {
        return userId;
    }
}

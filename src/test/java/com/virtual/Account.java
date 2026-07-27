package com.virtual;

import com.virtual.api.T;
import lombok.Getter;
import lombok.Setter;

/**
 * @author zhuchuanji
 * @Description todo
 * @Create: 2026/6/21 17:50
 */
@Getter
@Setter
@T
public class Account extends TableDefine<Integer> {

    private int id;
    private int count;

    @Override
    public Integer primaryKey() {
        return id;
    }
}

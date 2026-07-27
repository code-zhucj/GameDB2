package com.virtual;

import com.virtual.api.TableProvider;
import lombok.AllArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author zhuchuanji
 * @Description todo
 * @Create: 2026/5/31 5:51
 */
public enum MyTableProvider implements TableProvider {
    INSTANCE;

    @Override
    public <E extends TableDefine<?>> E create(Class<? extends TableDefine<?>> tableClass) {
        return (E) Provider.providers.get(tableClass).supplier.get();
    }

    @Override
    public Class<? extends TableDefine<?>> getProxyClass(Class<? extends TableDefine<?>> tableClass) {
        return Provider.providers.get(tableClass).proxyClass;
    }

    @Override
    public Iterable<Class<? extends TableDefine<?>>> allTableClass() {
        return Provider.providers.keySet();
    }

    @AllArgsConstructor
    private enum Provider {
        USER(User.class, _User.class, _User::new),
        ACCOUNT(Account.class, _Account.class, _Account::new),
        ;
        private final Class<? extends TableDefine<?>> tableClass;
        private final Class<? extends TableDefine<?>> proxyClass;
        private final Supplier<? extends TableDefine<?>> supplier;

        private final static Map<Class<? extends TableDefine<?>>, Provider>
                providers = Arrays.stream(values()).collect(Collectors.toMap(v -> v.tableClass, v -> v));
    }


}

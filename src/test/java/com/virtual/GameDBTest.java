package com.virtual;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

@Slf4j
class GameDBTest {

    @BeforeEach
    void setUp() {
        Tables.init(MyTableProvider.INSTANCE);
    }

    @AfterEach
    void tearDown() {
    }

    /**
     * 正确性测试
     */
    @Test
    void testTrue() {
        new TradeLogic(1, 2, 100).submit();

        new Logic() {
            @Override
            public State process() throws Exception {
                Table<Account> table = Tables.getTable(Account.class);
                Account account1 = table.select(1);
                if (account1 == null) {
                    return State.RETRY;
                }
                Account account2 = table.select(2);
                log.info("account1:{} account2:{}", account1.getCount(), account2.getCount());
                return State.SUCCESS;
            }
        }.submit();
    }

    /**
     * 性能测试
     */
    @Test
    void testEffect() throws ExecutionException, InterruptedException {
//        new CreateUserLogic().submit();
        int testNum = 100_000;
        int id = 100_000;
        long startTime = System.currentTimeMillis();
        Set<Integer> accounts = new HashSet<>();
        ThreadLocalRandom current = ThreadLocalRandom.current();
        for (int i = 0; i < testNum; i++) {
            int fromId = current.nextInt(id);
            int toId = current.nextInt(id);
            accounts.add(fromId);
            accounts.add(toId);
            new TradeLogic(fromId, toId, current.nextInt(10_0000)).submit();
        }

        while (TransactionImpl.existLogic()) {
            LockSupport.parkNanos(1000000);
        }

        new CheckLogic(accounts).submit().get();
        log.info("执行 {} 次, {} 个账号互相转账,耗时 {} ms", testNum, accounts.size(), System.currentTimeMillis() - startTime);
    }

    @Slf4j
    @AllArgsConstructor
    private static class CheckLogic implements Logic {
        private final Set<Integer> accounts;

        @Override
        public State process() throws Exception {
            Table<Account> table = Tables.getTable(Account.class);
            int num = 0;
            for (Integer id : accounts) {
                Account account = table.select(id);
                num += account.getCount();
            }
            if (num != 0) {
                log.error("事物错误！！！");
            } else {
                log.info("事物处理完成,共处理Account {}", accounts.size());
            }
            return State.SUCCESS;
        }
    }

    /**
     * 交易
     */
    @AllArgsConstructor
    @Slf4j
    private static class TradeLogic implements Logic {

        private final int fromId;
        private final int toId;
        private final int num;
        private static boolean IO = false;
        // 10C = IO 15s CPU 692ms
        // 1C = IO 145s CPU 675ms
        @Override
        public State process() throws Exception {
            Account fromAccount = getAndInsert(fromId);
            Account toAccount = getAndInsert(toId);
            fromAccount.setCount(fromAccount.getCount() - num);
            toAccount.setCount(toAccount.getCount() + num);

            if(IO){
                LockSupport.parkNanos(1_000_000); // IO 模拟
            } else {
                int x = 0;
                ThreadLocalRandom current = ThreadLocalRandom.current();
                for (int i = 0; i < 1000; i++) {
                    x += current.nextInt(100);
                }
            }

//            log.debug("{} => {} : {}", fromId, toId, num);
            return State.SUCCESS;
        }

        private Account getAndInsert(int id) {
            Table<Account> table = Tables.getTable(Account.class);
            Account account = table.select(id);
            if (account == null) {
                account = Tables.create(Account.class);
                account.setId(id);
                table.insert(account);
            }
            return account;
        }
    }

    @Slf4j
    private static class CreateUserLogic implements Logic {

        @Override
        public State process() {
            Table<User> userTable = Tables.getTable(User.class);
            User user = userTable.select(1); // _User
            if (user == null) {
                log.info("user is null");
                userTable.insert(user = Tables.create(User.class));
            }
            user.setName("张三");
            user.setUserId(1);
            return State.SUCCESS;
        }
    }
}
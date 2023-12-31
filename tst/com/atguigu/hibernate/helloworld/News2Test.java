package com.atguigu.hibernate.helloworld;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;

import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.LazyInitializationException;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.PersistentObjectException;
import org.hibernate.Session;
import org.hibernate.SessionException;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.hibernate.jdbc.Work;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.service.ServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class News2Test {
    private SessionFactory sessionFactory;
    // 在实际开发中，Session和Transaction是不能作为成员变量的，因为可能存在并发的问题。
    private Session session;
    private Transaction transaction;

    @BeforeEach
    public void init() {
        System.out.println("init");
        Configuration configuration = new Configuration().configure();
        ServiceRegistry serviceRegistry =
                new ServiceRegistryBuilder().applySettings(configuration.getProperties())
                                            .buildServiceRegistry();
        sessionFactory = configuration.buildSessionFactory(serviceRegistry);

        session = sessionFactory.openSession();
        transaction = session.beginTransaction();
    }

    @AfterEach
    public void destroy() {
        transaction.commit();
        session.close();
        sessionFactory.close();
        System.out.println("destroyed");
    }

    /**
     * 只发送了一条SELECT语句
     * 由于Session缓存中已经存在目标对象，第二次get()只是将缓存中对象的引用赋给news1，而没有再次执行SELECT操作。
     */
    @Test
    public void testSessionCache() {
        News news = (News) session.get(News.class, 1);
        System.out.println(news);   // News{id=1, title='Java1', author='SUN', date=2023-07-04}

        News news1 = (News) session.get(News.class, 1);
        System.out.println(news1);  // News{id=1, title='Java1', author='SUN', date=2023-07-04}

        assertTrue(news == news1);
    }

    /**
     * flush: 使数据表中的对象和Session缓存中的对象的状态保持一致。为了保持一致，则可能会发送对应的SQL语句。
     * 1. 调用Transaction的commit()方法中：先调用Session.flush(),再提交事务
     * 2. flush()方法可能会发送SQL语句，但不会提交事务。
     * 3. 注意：在未提交事务or显式的调用session.flush()方法之前，也有可能会执行flush()操作
     *  1）执行HQL或QBC查询，会先进行一次flush操作，以得到数据表的最新记录。
     *  2）若记录的ID是由底层数据库使用自增的方法生成的，则在调用save()方法后，就会立即发送INSERT语句。因为save()方法后，必须保证对象的ID是存在的。
     */
    @Test
    public void testSessionFlush() {
        News news = (News) session.get(News.class, 1);  // 发送SELECT语句
        System.out.println(news);   // News{id=1, title='Java1', author='SUN', date=2023-07-04}
        news.setAuthor("Oracle");   // 在flush时，会发送UPDATE语句：news.setAuthor("Oracle")会改变Session中对象的状态，导致与DB中对象的状态不一致
        // Session会检测到不同。在transaction.commit()之前，Session会发送flush语句，也就是一条UPDATE语句
    }

    @Test
    public void testSessionFlush1() {
        News news = (News) session.get(News.class, 1);  // 发送SELECT语句
        System.out.println(news);   // News{id=1, title='Java1', author='SUN', date=2023-07-04}
        news.setAuthor("Oracle");

        News news1 = (News) session.createCriteria(News.class).uniqueResult();  // 通过QBC查询出来的就是最新的状态，i.e. author="Oracle"
        System.out.println(news1);  // News{id=1, title='Java1', author='Oracle', date=2023-07-04}
    }

    /**
     * <generator class="native"/>
     * 如果ID的generator是native，也就是由底层数据库使用自增的方式，则在调用save()方法后，就会立即发送INSERT语句。因为save()方法后，必须保证对象的ID是存在的。
     *
     * <generator class="hilo"/>
     * 如果将ID的generator改为 hilo，则在session.save()执行之后，在transaction.commit()之前，会发送SELECT和UPDATE语句，没有INSERT语句
     * SELECT和UPDATE是来确认ID值的。
     */
    @Test
    public void testSessionFlush2() {
        News news = new News("Java1", "atguigu", new java.sql.Date(new Date().getTime()));
        session.save(news);
    }

    /**
     * refresh(): 会强制发送SELECT语句，以使Session缓存中对象的状态和数据表中对应的记录保持一致
     * <property name="connection.isolation">2</property> 设置Hibernate的事务隔离级别为READ COMMITTED
     */
    @Test
    public void testRefresh() {
        News news = (News) session.get(News.class, 1);  // 发送了一条SELECT语句
        System.out.println(news);   // News{id=1, title='Java1', author='atguigu', date=2023-07-08}
        // 第二次打印之前，手动更改DB
        session.refresh(news);      // 会再次发送SELECT语句
        System.out.println(news);   // News{id=1, title='Java1', author='atguigu', date=2023-07-08}
    }

    /**
     * clean(): 清理缓存
     */
    @Test
    public void testClear() {
        News news1 = (News) session.get(News.class, 1);
        News news2 = (News) session.get(News.class, 1); //call了两次get()方法，由于Session发缓存的存在，只送了一条SELECT语句
    }

    @Test
    public void testClear1() {
        News news1 = (News) session.get(News.class, 1); // 发送了一条SELECT语句
        session.clear();
        News news2 = (News) session.get(News.class, 1); // 再次发送了一条SELECT语句
    }

    /**
     * session.contains(news)=false
     */
    @Test
    public void testTransientState() {
        News2 news = new News2("Java", "atguigu", new java.util.Date());
        System.out.println("session.contains(news)=" + session.contains(news)); // false

        assertFalse(session.contains(news));
    }

    /**
     * session.contains(news)=false
     * Hibernate:
     *     insert
     *     into
     *         NEWS2
     *         (TITLE, AUTHOR, DATE)
     *     values
     *         (?, ?, ?)
     * session.contains(news)=true
     */
    @Test void testPersistentStateSave() {
        News2 news = new News2("Java", "atguigu", new java.util.Date());
        System.out.println("session.contains(news)=" + session.contains(news)); // false
        session.save(news); // flush时，发送一条INSERT语句
        System.out.println("session.contains(news)=" + session.contains(news)); // true

        assertTrue(session.contains(news));
    }

    /**
     * session.contains(news)=false
     * Hibernate:
     *     insert
     *     into
     *         NEWS2
     *         (TITLE, AUTHOR, DATE)
     *     values
     *         (?, ?, ?)
     * session.contains(news)=true
     */
    @Test void testPersistentStatePersist() {
        News2 news = new News2("Java", "atguigu", new java.util.Date());
        System.out.println("session.contains(news)=" + session.contains(news)); // false
        session.persist(news); // flush时，发送一条INSERT语句
        System.out.println("session.contains(news)=" + session.contains(news)); // true

        assertTrue(session.contains(news));
    }

    /**
     * session.contains(news)=false
     * Hibernate:
     *     insert
     *     into
     *         NEWS2
     *         (TITLE, AUTHOR, DATE)
     *     values
     *         (?, ?, ?)
     * session.contains(news)=true
     * session.isOpen()=false
     */
    @Test
    public void testDetachedState() {
        News2 news = new News2("Java", "atguigu", new java.util.Date());
        System.out.println("session.contains(news)=" + session.contains(news)); // false
        session.persist(news); // flush时，发送一条INSERT语句
        System.out.println("session.contains(news)=" + session.contains(news)); // true

        session.close();
        assertFalse(session.isOpen());
        System.out.println("session.isOpen()=" + session.isOpen());     // false
        SessionException exception = assertThrows(SessionException.class, () -> session.contains(news)); //org.hibernate.SessionException: Session is closed!
        assertTrue(exception.getMessage().contains("Session is closed!"));
        session = sessionFactory.openSession();
    }

    /**
     * save()方法
     * 1. 使一个临时对象(transient)变为持久化对象(persist)
     * 2. 为对象分配ID
     * 3. 在flush缓存时，会发送一条INSERT语句
     * 4. 在save()方法之前的ID是“无效的”，see testSave2()，并不会抛出异常。
     * 5. 持久化对象的ID是不能被修改的！
     */
    @Test
    public void testSave() {
        News2 news = new News2("AA", "aa", new Date());

        System.out.println(news);   // News{id=null, title='AA', author='aa', date=Sat Jul 08 11:34:28 PDT 2023}
        System.out.println("session.contains(news)=" + session.contains(news)); // session.contains(news)=false
        session.save(news);
        System.out.println(news);   // News{id=23, title='AA', author='aa', date=Sat Jul 08 11:34:28 PDT 2023}
        System.out.println("session.contains(news)=" + session.contains(news)); // session.contains(news)=true
    }


    @Test
    public void testSave2() {
        News2 news = new News2("AA", "aa", new Date());
        news.setId(100);
        System.out.println(news);   // News{id=100, title='AA', author='aa', date=Sat Jul 08 12:06:37 PDT 2023}
        session.save(news);
        System.out.println(news);   // News{id=48, title='AA', author='aa', date=Sat Jul 08 12:06:37 PDT 2023}
    }

    @Test
    public void testSave3() {
        News2 news = new News2("BB", "bb", new Date());
        news.setId(100);

        System.out.println(news);   // News{id=100, title='BB', author='bb', date=Sat Jul 08 12:11:08 PDT 2023}
        session.save(news);
        System.out.println(news);   // News{id=52, title='BB', author='bb', date=Sat Jul 08 12:11:08 PDT 2023}

        news.setId(101);
        System.out.println(news);   // News{id=101, title='BB', author='bb', date=Sat Jul 08 12:11:08 PDT 2023}

        HibernateException exception = assertThrows(HibernateException.class, () -> transaction.commit());

        final String msg = String.format("identifier of an instance of %s was altered from", news.getClass().getName());
        System.out.println(msg);
        assertTrue(exception.getMessage().contains(msg));

        session.close();
        session = sessionFactory.openSession();
    }

    /**
     * persist():
     * 1. 也会执行INSERT操作
     *
     * 和save()的区别:在调用persist()方法之前，若对象已经有ID了，则不会执行INSERT，而是抛出PersistentObjectException异常。
     */
    @Test
    public void testPersist() {
        News2 news = new News2("DD", "dd", new Date());

        System.out.println(news);   // News{id=null, title='DD', author='dd', date=Sat Jul 08 12:18:30 PDT 2023}
        session.persist(news);      // 发出INSERT语句
        System.out.println(news);   // News{id=53, title='DD', author='dd', date=Sat Jul 08 12:18:30 PDT 2023}
    }

    @Test
    public void testPersist2() {
        News2 news = new News2("DD", "dd", new Date());
        news.setId(100);

        System.out.println(news);   // News{id=100, title='DD', author='dd', date=Sat Jul 08 12:19:40 PDT 2023}
        // Throws exception: org.hibernate.PersistentObjectException: detached entity passed to persist: com.atguigu.hibernate.helloworld.News2
        assertThrows(PersistentObjectException.class, () -> session.persist(news));
    }

    /**
     * get() v.s. load():
     * 1. 执行get(): 会立即加载对象；==> get是立即检索
     *    而执行load()，若不使用该对象，则不会立即执行查询操作，而是返回一个代理对象。==> load是延迟检索
     * 2. load() 方法可能会抛出：LazyInitializationException
     *    在需要初始化代理对象之前关闭Session，就会抛出LazyInitializationException
     * 3. 若数据表中没有对应的记录，且Session没有关闭：
     *    get() 返回null
     *    load() 若不使用该对象的任何属性，没有问题；若需要初始化了，则抛出异常。
     */
    @Test
    public void testGet() {
        News2 news = (News2) session.get(News2.class, 1);   // get()是立即检索
        System.out.println(news);   // News{id=1, title='Java', author='atguigu', date=2023-07-04 23:12:12.0}
    }

    @Test
    public void testGet1() {
        News2 news = (News2) session.get(News2.class, 100); // 当数据库中不存在与OID对应的记录时, get()返回null
        System.out.println(news);   // null
    }

    @Test
    public void testLoad() {
        News2 news = (News2) session.load(News2.class, 1);
        System.out.println(news);   // News{id=1, title='Java', author='atguigu', date=2023-07-04 23:12:12.0}
        System.out.println(news.getClass().getName());  // com.atguigu.hibernate.helloworld.News2_$$_jvstf23_1
    }

    /**
     * session.load()是延迟加载。当需要使用load的返回对象，但是对象不存在时，会抛吃ObjectNotFoundException
     */
    @Test
    public void tesLoad1() {
        News2 news = (News2) session.load(News2.class, 100);
        System.out.println(news.getClass().getName());
        try {
            System.out.println(news);
        } catch (Exception e) {
            e.printStackTrace();
            assertTrue(e instanceof ObjectNotFoundException);
        }
    }

    /**
     * 若数据表中没有对应的记录，且Session没有关闭：
     * load()若不使用该对象的任何属性，没有问题；若需要初始化了，则抛出异常。
     */
    @Test
    public void tesLoad2() {
        News2 news = (News2) session.load(News2.class, 100);
        System.out.println(news.getClass().getName());  // com.atguigu.hibernate.helloworld.News2_$$_jvst3f_1
        session.close();
        try {
            System.out.println(news);   // org.hibernate.LazyInitializationException: could not initialize proxy - no Session
        } catch (Exception e) {
            e.printStackTrace();
            assertTrue(e instanceof LazyInitializationException);
        }

        session = sessionFactory.openSession();
    }

    /**
     * update():
     * 1. 若更新一个持久化对象，不需要显式调用update方法，因为在调用Transaction.commit()方法时，会先执行Session.flush()方法
     * 2. 更新一个游离对象，需要显式的调用session的update方法。可以把一个游离对象变为持久化对象
     *
     * 需要注意的：
     * 1. 无论需要更新的游离对象和数据表中的记录是否一致，都会发送UPDATE语句。
     * 如何能让update方法不再盲目地发出UPDATE语句？在.hbm.xml文件的class节点设置select-before-update=true (default=false)。但通常不需要设置该属性
     * 2. 若数据表中没有记录，但还调用了update方法，会抛出异常。 see testUpdate5()
     * 3. 当update()方法关联一个游离对象时，如果在Session的缓存中已经有了相同的OID对象，会抛出异常。因为在Session缓存中不能有两个OID相同的对象。
     */
    @Test
    public void testUpdate() {
        News2 news = (News2) session.get(News2.class, 1);   // 会发送一条SELECT语句
        news.setAuthor("Oracle");

        //session.update(news); // 可以省略，transaction.commit()时，会发送一条UPDATE语句
    }

    @Test
    public void testUpdate2() {
        News2 news = (News2) session.get(News2.class, 1);
        transaction.commit();
        session.close();    // session close之后，缓存会清空

        session = sessionFactory.openSession(); // 开启新的session
        transaction = session.beginTransaction();   // 开启新的transaction

        // 此时news并不在新的session中，是一个游离对象
        // 此时想要update对象。则需要显式的update
        news.setAuthor("SUN");
        session.update(news);   // 会发送一条UPDATE语句
    }

    @Test
    public void testUpdate3() {
        News2 news = (News2) session.get(News2.class, 1);
        transaction.commit();
        session.close();    // session close之后，缓存会清空

        session = sessionFactory.openSession(); // 开启新的session
        transaction = session.beginTransaction();   // 开启新的transaction

        session.update(news);   // 即便对象是原封不对的，也会发送UPDATE语句。因为新的session并不知道数据表中的状态
    }

    /**
     * select-before-update=false or default
     *  update()会发送一条UPDATE语句，无论要更新的对象是否发生变化
     *
     * select-before-update=true
     *  update()会发送一条SELECT语句，检查Session缓存中的对象和DB中对象是否由差异，无差异的话，便不会发送UPDATE语句
     */
    @Test
    public void testUpdate4() {
        News2 news = (News2) session.get(News2.class, 1);
        transaction.commit();
        session.close();    // session close之后，缓存会清空

        session = sessionFactory.openSession(); // 开启新的session
        transaction = session.beginTransaction();   // 开启新的transaction

        session.update(news);   // 此时对象是原封不对的，因为设置了select-before-update=true，在update之前会执行一次SELECT，此时不会盲目触发UPDATE
    }

    @Test
    public void testUpdate5() {
        News2 news = (News2) session.get(News2.class, 1);
        transaction.commit();
        session.close();    // session close之后，缓存会清空

        news.setId(100);

        session = sessionFactory.openSession(); // 开启新的session
        transaction = session.beginTransaction();   // 开启新的transaction

        // transaction.commit() 会抛出: org.hibernate.StaleStateException: Batch update returned unexpected row count from update [0]; actual row count: 0; expected: 1
        session.update(news);  // 若数据表中没有记录，但还调用了update方法，会抛出异常
    }

    @Test
    public void testUpdate6() {
        News2 news = (News2) session.get(News2.class, 1);
        transaction.commit();
        session.close();    // session close之后，缓存会清空

        session = sessionFactory.openSession(); // 开启新的session
        transaction = session.beginTransaction();   // 开启新的transaction
        News2 news2 = (News2) session.get(News2.class, 1);
        session.update(news);  // org.hibernate.NonUniqueObjectException: a different object with the same identifier value was already associated with the session: [com.atguigu.hibernate.helloworld.News2#1]
        // 在同一个Session的缓存中，不能存在两个相同OID的对象: news和news2的OID都是1
    }

    /**
     * Session的saveOrUpdate()方法同时包含了save()与update()方法的功能
     *   * 当对象是游离对象时，执行update()方法
     *   * 当对象是临时对象时，执行save()方法
     * 判定对象为临时对象的标准：
     *   * Java对象的OID为null
     *   * 映射文件中为`<id>`设置了`unsaved-value`属性, 并且Java对象的OID取值与这个`unsaved-value`属性值匹配
     */
    @Test
    public void testSaveOrUpdate() {
        News2 news = new News2("EE", "ee", new Date()); // 此时OID为null, news为临时对象，执行save方法，也就是发出INSERT语句
        System.out.println(news);   // News{id=null, title='EE', author='ee', date=Thu Jul 06 22:54:23 PDT 2023}
        session.saveOrUpdate(news);
        System.out.println(news);   // News{id=6, title='EE', author='ee', date=Thu Jul 06 22:54:23 PDT 2023}
    }

    /**
     * 注意：
     * 1. 若OID不为空，但数据表中还没有和其对应的记录，会抛出一个异常
     * 2. 了解内容：若OID值等于id的unsaved-value属性值的对象，也会被认为是一个游离对象。
     */
    @Test
    public void testSaveOrUpdate1() {
        News2 news = new News2("FFF", "fff", new Date());
        news.setId(100);
        System.out.println(news);   // News{id=100, title='FFF', author='fff', date=Sun Jul 09 12:27:40 PDT 2023}
        session.saveOrUpdate(news); // 执行update方法，并抛出 org.hibernate.StaleStateException: Batch update returned unexpected row count from update [0]; actual row count: 0; expected: 1
        System.out.println(news);   // News{id=100, title='FFF', author='fff', date=Sun Jul 09 12:27:40 PDT 2023}
    }

    @Test
    public void testSaveOrUpdate2() {
        News2 news = new News2("FFF", "eee", new Date());
        news.setId(10);
        System.out.println(news);   // News{id=10, title='FFF', author='eee', date=Sun Jul 09 12:28:31 PDT 2023}
        session.saveOrUpdate(news); // 判定为游离对象(news在缓存中存在)，执行update方法，发送UPDATE语句
        System.out.println(news);   // News{id=10, title='FFF', author='eee', date=Sun Jul 09 12:28:31 PDT 2023}
    }

    @Test
    public void testSaveOrUpdate3() {
        News2 news = new News2("FFF", "ee7", new Date());
        news.setId(11);
        System.out.println(news);   // News{id=11, title='FFF', author='ee7', date=Thu Jul 06 23:02:23 PDT 2023}
        session.saveOrUpdate(news); // 此时id=11 == unsaved-value，即便数据表中不存在该记录，也会执行saveOrUpdate，会执行save方法，发送INSERT语句
        System.out.println(news);   // News{id=7, title='FFF', author='ee7', date=Thu Jul 06 23:02:23 PDT 2023}
    }

    /**
     * delete(): 执行删除操作，只要OID和数据表中的一条记录对应，就会准备执行delete操作。若OID在数据表中没有对应记录，则抛出异常。
     *
     * 可以通过设置Hibernate配置文件，hibernate.use_identifier_rollback为true，使删除对象后，把其OID设置为null
     */
    @Test
    public void testDelete() {
        News2 news = new News2();
        news.setId(1);  // 此时news是一个游离对象(Detached): OID!=null && session中并“不存在”该对象

        session.delete(news);   // 发送DELETE语句，对应记录被删除
    }

    @Test
    public void testDelete2() {
        News2 news = (News2) session.get(News2.class, 2); // 此时news是一个持久化对象(Persist): OID!=null && session中并“存在”该对象

        session.delete(news);   // 发送DELETE语句，对应记录被删除
    }

    @Test
    public void testDelete3() {
        News2 news = new News2();
        news.setId(11);         // 此时news是一个游离对象(Detached)，数据表中没有对应记录

        session.delete(news);   // 发送DELETE语句，抛出：org.hibernate.StaleStateException: Batch update returned unexpected row count from update [0]; actual row count: 0; expected: 1
    }

    @Test
    public void testDelete4() {
        News2 news = (News2) session.get(News2.class, 8); // 此时news是一个持久化对象(Persist): OID!=null && session中并“存在”该对象

        session.delete(news);       // 发送DELETE语句，对应记录被删除。但是DELETE语句并非立即发送，而是在flush时发送，所以下面依然可以打印
        System.out.println(news);   // News{id=3, title='BB', author='bb', date=2023-07-06 21:15:47.0}
    }

    @Test
    public void testDelete5() {
        News2 news = (News2) session.get(News2.class, 5); // 此时news是一个持久化对象(Persist): OID!=null && session中并“存在”该对象
        System.out.println(news);   // News{id=5, title='DD', author='dd', date=2023-07-06 21:20:04.0}
        session.delete(news);       // 发送DELETE语句，对应记录被删除。但是DELETE语句并非立即发送，而是在flush时发送，所以下面依然可以打印
        System.out.println(news);   // News{id=null, title='DD', author='dd', date=2023-07-06 21:20:04.0}, 即使此时还没有发送DELETE语句，OID也已经被设置为null了
    }

    /**
     * evict(): 从session缓存中把指定的持久化对象移除
     */
    @Test
    public void testEvict() {
        News2 news1 = (News2) session.get(News2.class, 1);
        News2 news2 = (News2) session.get(News2.class, 2);

        news1.setAuthor("AAA");
        news2.setAuthor("BBB");

        session.evict(news1);   // 有两条SELECT语句，一条UPDATE语句，news1没有被更改
    }

    /**
     * doWork(): 直接通过JDBC API来访问数据库的操作
     *
     * 当配置C3P0后，测试打印出来的数据库连接是: ## connection=com.mchange.v2.c3p0.impl.NewProxyConnection@5c089b2f
     */
    @Test
    public void testDoWork() {
        session.doWork(new Work() {
            @Override
            public void execute(Connection connection) throws SQLException {
                System.out.println("## connection=" + connection);  // ## connection=com.mysql.jdbc.JDBC4Connection@f9b5552，即原生的JDBC connection
                // 然后用connection 调用存储过程
                // String procedure = String.format("UPDATE news2 SET author = %s WHERE id = %d", "Oracle", 9); // Exception
                // CallableStatement callableStatement = connection.prepareCall(procedure);
                // callableStatement.executeUpdate();
            }
        });
    }

    /**
     * 代码中只设置了author字段，但是Hibernate发出的UPDATE语句把3个字段都更新了，说明这个UPDATE语句并不是动态生成的。
     * Hibernate:
     *     update
     *         NEWS2
     *     set
     *         TITLE=?,
     *         AUTHOR=?,
     *         DATE=?
     *     where
     *         ID=?
     */
    @Test
    public void testDynamicUpdate() {
        News2 news2 = (News2) session.get(News2.class, 1);
        news2.setAuthor("~~~~~");
    }

    /**
     * 在News2.hbm.xml的<class>中设置 dynamic-update="true"
     * 此时，Hibernate发出的UPDATE语句就只包含要更新的字段。
     * Hibernate:
     *     update
     *         NEWS2
     *     set
     *         AUTHOR=?
     *     where
     *         ID=?
     */
    @Test
    public void testDynamicUpdate1() {
        News2 news2 = (News2) session.get(News2.class, 1);
        news2.setAuthor("ABCD");
    }


    /**
     * <generator class="increment"/>
     * Hibernate:
     *     select
     *         max(ID)
     *     from
     *         NEWS2
     * Hibernate:
     *     insert
     *     into
     *         NEWS2
     *         (TITLE, AUTHOR, DATE, ID)
     *     values
     *         (?, ?, ?, ?)
     *
     * 会发出SELECT语句查询最大的ID，然后执行插入操作。
     *
     * 使用 increment generator 存在并发的问题。
     */
    @Test
    public void testGeneratorIncrement() {
        News2 news2 = new News2("AAA", "author=aaa", new Date());
        session.save(news2);
    }

    /**
     * 在5s之内run这个test两次，会出现异常，第二次的save操作失败，数据没有插入到DB
     * Caused by: com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException: Duplicate entry '57' for key 'news2.PRIMARY'
     * 	at java.base/jdk.internal.reflect.NativeConstructorAccessorImpl.newInstance0(Native Method)
     * 	at java.base/jdk.internal.reflect.NativeConstructorAccessorImpl.newInstance(NativeConstructorAccessorImpl.java:62)
     * 	at java.base/jdk.internal.reflect.DelegatingConstructorAccessorImpl.newInstance(DelegatingConstructorAccessorImpl.java:45)
     * 	at java.base/java.lang.reflect.Constructor.newInstance(Constructor.java:490)
     */
    @Test
    public void testGeneratorIncrement1() throws InterruptedException {
        News2 news2 = new News2("AAA", "author=aaa", new Date());
        session.save(news2);

        Thread.sleep(5000);
    }


    /**
     * <generator class="identity"/>
     * Hibernate:
     *     insert
     *     into
     *         NEWS2
     *         (TITLE, AUTHOR, DATE)
     *     values
     *         (?, ?, ?)
     * Hibernate 发出的INSERT语句只包含三个fields，而不包含id，说明使用的是由底层数据库生成。
     */
    @Test
    public void testGeneratorIdentity() {
        News2 news2 = new News2("AAA", "author=aaa", new Date());
        session.save(news2);
    }

    /**
     * table="NEWS2_hilo"
     * <generator class="hilo"/>
     * 会生成一张额外的表：table hibernate_unique_key
     *
     * Hibernate:
     *     select
     *         next_hi
     *     from
     *         hibernate_unique_key for update
     *
     * Hibernate:
     *     update
     *         hibernate_unique_key
     *     set
     *         next_hi = ?
     *     where
     *         next_hi = ?
     * Hibernate:
     *     insert
     *     into
     *         NEWS2_hilo
     *         (TITLE, AUTHOR, DATE, ID)
     *     values
     *         (?, ?, ?, ?)
     */
    @Test
    public void testGeneratorHilo() {
        News2 news2 = new News2("AAA", "author=aaa", new Date());
        session.save(news2);
    }

    /**
     * cfg.xml:
     *  <property name="hbm2ddl.auto">create</property>
     * hbm.xml:
     *  <property name="date" type="date">
     *      <column name="DATE"/>
     *  </property>
     *
     * DB中显式的Date为：2023-07-15，只有日期，没有时间
     */
    @Test
    public void testDate() {
        News2 news2 = new News2("AAA", "author=aaa", new Date());
        System.out.println("Before session.save(), news2=" + news2);    // news2=News{id=null, title='AAA', author='author=aaa', date=Sat Jul 15 18:45:32 PDT 2023}

        session.save(news2);

        System.out.println("After session.save(), news2=" + news2);     // news2=News{id=1, title='AAA', author='author=aaa', date=Sat Jul 15 18:45:32 PDT 2023}
        System.out.println("news2.getDate()=" + news2.getDate());       // Sat Jul 15 18:45:32 PDT 2023
        System.out.println("news2.getDate().getClass()=" + news2.getDate().getClass()); // java.util.Date
    }

    /**
     * cfg.xml:
     *  <property name="hbm2ddl.auto">create</property>
     * hbm.xml:
     *  <property name="date" type="time">
     *      <column name="DATE"/>
     *  </property>
     *
     * DB中显式的Date为：18:48:15，只有时间，没有日期
     */
    @Test
    public void testTime() {
        News2 news2 = new News2("AAA", "author=aaa", new Date());
        System.out.println("Before session.save(), news2=" + news2);    // news2=News{id=null, title='AAA', author='author=aaa', date=Sat Jul 15 18:48:15 PDT 2023}

        session.save(news2);

        System.out.println("After session.save(), news2=" + news2);     // news2=News{id=1, title='AAA', author='author=aaa', date=Sat Jul 15 18:48:15 PDT 2023}
        System.out.println("news2.getDate()=" + news2.getDate());       // Sat Jul 15 18:48:15 PDT 2023
        System.out.println("news2.getDate().getClass()=" + news2.getDate().getClass()); // java.util.Date
    }

    /**
     * cfg.xml:
     *  <property name="hbm2ddl.auto">create</property>
     * hbm.xml:
     *  <property name="date" type="timestamp">
     *      <column name="DATE"/>
     *  </property>
     *
     * DB中显式的Date为：2023-07-15 18:50:28，既有时间，也有日期
     */
    @Test
    public void testTimestamp() {
        News2 news2 = new News2("AAA", "author=aaa", new Date());
        System.out.println("Before session.save(), news2=" + news2);    // news2=News{id=null, title='AAA', author='author=aaa', date=Sat Jul 15 18:50:27 PDT 2023}

        session.save(news2);

        System.out.println("After session.save(), news2=" + news2);     // news2=News{id=1, title='AAA', author='author=aaa', date=Sat Jul 15 18:50:27 PDT 2023}
        System.out.println("news2.getDate()=" + news2.getDate());       // Sat Jul 15 18:50:27 PDT 2023
        System.out.println("news2.getDate().getClass()=" + news2.getDate().getClass()); // java.util.Date
    }

    /**
     * 若content和image的映射类型为：
     *  <property name="content" type="clob"></property>
     *  <property name="image" type="blob"></property>
     * 此时，MySQL DB中，content的Data Type是longtext，image的Data Type是longblob
     *
     * 如果想要更精准的映射content和image，则可以使用`sql-type`
     *  <property name="content">
     *      <column name="CONTENT" sql-type="mediumtext"/>
     *  </property>
     *  <property name="image">
     *      <column name="IMAGE" sql-type="mediumblob"/>
     *  </property>
     * 此时，MySQL DB中，content的Data Type是mediumtext，image的Data Type是mediumblob
     */
    @Test
    public void testInsertLargeObject() {
        News2 news2 = new News2("AAA", "author=aaa", new Date());
        System.out.println("Before session.save(), news2=" + news2);    // news2=News{id=null, title='AAA', author='author=aaa', date=Sat Jul 15 18:48:15 PDT 2023}

        session.save(news2);
    }

    @Test
    public void testBlobTypeSave() throws IOException {
        News2 news2 = new News2("AAA", "author=aaa", new Date());
        news2.setContent("CONTENT~!~");

        InputStream inputStream = new FileInputStream("tree-736885_1280.jpg");
        Blob image = Hibernate.getLobCreator(session).createBlob(inputStream, inputStream.available());
        news2.setImage(image);
        session.save(news2);
    }

    /**
     * <property name="hbm2ddl.auto">update</property>
     * @throws IOException
     *
     * 实际应用中通常会保存image path，而不是image directly
     */
    @Test
    public void testBlobTypeGet() throws IOException, SQLException {
        News2 news2 = (News2) session.get(News2.class, 1);
        Blob image = news2.getImage();
        InputStream inputStream = image.getBinaryStream();
        System.out.println(inputStream.available());    // 185491, Size of image "tree-736885_1280.jpg" is 185,491 bytes (188 KB on disk)
    }

}
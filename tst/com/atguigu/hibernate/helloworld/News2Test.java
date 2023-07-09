package com.atguigu.hibernate.helloworld;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import org.hibernate.HibernateException;
import org.hibernate.LazyInitializationException;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.PersistentObjectException;
import org.hibernate.Session;
import org.hibernate.SessionException;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
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
}
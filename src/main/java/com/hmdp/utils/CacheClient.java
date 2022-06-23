package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static com.hmdp.utils.RedisConstants.*;

/**
 * 封装redis 缓存重建工具类
 */
@Slf4j
@Component
public class CacheClient {
    //这里可以构造函数，也可以加@Resource注解
    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //写入缓存的set方法
    /**
     * 这里是正常的存入缓存
     * @param key 传入存到redis的key
     * @param value 传入存到redis的value
     * @param time 传入过期时间
     * @param unit 传入过期时间的单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        //Object value为泛型，以后其他的类也能调用
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    /**
     * 这里是逻辑过期
     * @param key 传入存到redis的key
     * @param value 传入存到redis的value
     * @param time 传入逻辑过期时间
     * @param unit 传入逻辑过期单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //Object value为泛型，以后其他的类也能调用
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
    //读取缓存的get方法

    /**
     * 缓存穿透
     * @param keyHead 传入存入redis的key的前缀
     * @param id 传入id
     * @param type 传入泛型的类型
     * @param dbFallback 传入一个函数（因为在先数据库查询时，这里的泛型不能与查询语句对应不起来）
     * @param time 传入缓存时间
     * @param unit 传入缓单位
     * @return 返回这个泛型
     * @param <R> 需要传入的类型
     * @param <ID> id可能多种类型
     */
    public <R,ID> R queryWithPassThrough(String keyHead, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyHead + id;
        //1、从redis查询店铺
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3、存在，直接返回
            //这里是不知道是什么类型，所以把传入的类型用作转化的类型
            return JSONUtil.toBean(json, type);
        }
        //判断是否是空
        if (json != null) {
            //TODO 命中，缓存通过后，需要判断缓存店铺信息是否为空（防止缓存穿透）
            return null;
        }
        //4、不存在，根据id查询数据库
        //函数式编程 传参时，传入一个函数，因为数据库的查询，需要对应实体类，这里是泛型
        R r = dbFallback.apply(id);//Shop shop = getById(id);
        //5、查询数据库还不存在
        if(r==null){
            //TODO 如果店铺信息在redis和MySQL中都不存在，则向redis中加入空缓存（防止缓存穿透）
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6、存在，写入redis
        /*
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL+Math.abs(new Random().nextInt(30)), TimeUnit.MINUTES);
         */
        this.set(key,r,time,unit);
        //7、返回
        return r;
    }
    //这里开有一个缓存池提供逻辑过期功能用
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     * @param keyHead 传入存入redis的消息头
     * @param id 传入id
     * @param type 传入泛型的类型
     * @param dbFallback 传入一个函数（因为在先数据库查询时，这里的泛型不能与查询语句对应不起来）
     * @param time 传入逻辑过期时间
     * @param unit 传入逻辑过期时间的单位
     * @return 返回泛型
     * @param <R> 定义的泛型
     * @param <ID> 定义的泛型
     */
    public <R,ID> R queryWithLogicalExpire(String keyHead,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyHead + id;
        //1、从redis查询店铺
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if(StrUtil.isBlank(json)){
            //3、不存在，直接返回
            return null;
        }
        //4、命中，需要把json反序列化为对象（因为这里有逻辑过期时间，所以得换两次）
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5、判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1，未过期，直接返回商品信息
            return r;
        }

        //5.2、过期，需要缓存重建
        //6、缓存重建
        //6.1、获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        //6.2、判断是否获取锁成功
        boolean isLock = tryLock(lockKey);

        if(isLock){
            //6.3、成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //重建缓存
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4、返回过期的商铺信息
        return r;
    }

    /**
     * 设置锁
     * @param key 传入锁的key值
     * @return 返回boolean类型
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //这里防止返回boolean类型时，出现控制异常，所以用工具包转化返回
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 删除锁
     * @param key 传入解除锁的key值
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}

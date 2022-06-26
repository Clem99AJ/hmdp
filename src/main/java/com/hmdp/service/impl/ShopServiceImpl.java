package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //封装好的工具类
    @Resource
    private CacheClient cacheClient;
    /**
     * 通过店铺id查询店铺数据
     * @param id 传入店铺id
     * @return 返回Result类型
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        Shop shop = queryWithPassThrough(id);
        //id2->getById(id2) 等效 this::getById
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿(活动用)
        //Shop shop = queryWithLogicalExpire(id);
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,10L,TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺信息不存在！");
        }
        //7、返回
        return Result.ok(shop);
    }

    //TODO 封装成工具类后，之后的代码都可以不要了
    //这里开有一个缓存池提供逻辑过期功能用
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 通过逻辑过期解决缓存击穿
     * @param id 传入店铺id
     * @return 返回Shop类型
     */
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1、从redis查询店铺
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //3、不存在，直接返回
            return null;
        }
        //4、命中，需要把json反序列化为对象（因为这里有逻辑过期时间，所以得换两次）
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5、判断是否过期

        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1，未过期，直接返回商品信息
            return shop;
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
                    this.saveShop2Redis(id,10L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4、返回过期的商铺信息
        return shop;
    }

    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1、从redis查询店铺
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3、存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中知否为空值
        if (shopJson != null) {
            //TODO 命中，缓存通过后，需要判断缓存店铺信息是否为空（防止缓存穿透）
            return null;
        }
        //TODO 4、实现缓存重建
        Shop shop = null;
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;

        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock){
                //4.3失败，则休眠重试
                Thread.sleep(50);
                //这里递归，直到命中
                return queryWithMutex(id);
            }

            //4.4锁通过后,成功，根据id查询数据库
            shop = getById(id);
                //因为查询的是本地数据库，很快，所以这里模拟一下延迟
            Thread.sleep(200);

            //5、查询数据库还不存在，返回错误
            if(shop==null){
                //TODO 如果店铺信息在redis和MySQL中都不存在，则向redis中加入空缓存（防止缓存穿透）
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6、存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL+Math.abs(new Random().nextInt(30)), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7、释放互斥锁
            unlock(lockKey);
        }
        //8、返回
        return shop;
    }

    /**
     * 把缓存穿透封装起来,用的时候直接调用
     * @param id 传入店铺id
     * @return 返回店铺类型
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1、从redis查询店铺
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3、存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        if (shopJson != null) {
            //TODO 命中，缓存通过后，需要判断缓存店铺信息是否为空（防止缓存穿透）
            return null;
        }
        //4、不存在，根据id查询数据库
        Shop shop = getById(id);
        //5、查询数据库还不存在，返回错误
        if(shop==null){
            //TODO 如果店铺信息在redis和MySQL中都不存在，则向redis中加入空缓存（防止缓存穿透）
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6、存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL+Math.abs(new Random().nextInt(30)), TimeUnit.MINUTES);
        //7、返回
        return shop;
    }

    /**
     * 设置锁
     * @param key 传入锁的key值
     * @return 返回boolean类型
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //这里防止返回boolean类型时，出现空指针异常，所以用工具包转化返回
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 删除锁
     * @param key 传入解除锁的key值
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 预热（模拟在活动开始前，在后台把缓存先存入）先把Redis
     * @param id 传入预热店铺的id
     */
    public void saveShop2Redis(Long id,Long expireSeconds)throws InterruptedException{
        //1、查询店铺信息
        Shop shop = getById(id);
        Thread.sleep(200);
        //2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3、写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }
    /**
     * 更新店铺数据
     * @param shop 传入Shop类型
     * @return 返回Result
     */
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1、更新数据库
        updateById(shop);
        //2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok("店铺更新成功");
    }
}

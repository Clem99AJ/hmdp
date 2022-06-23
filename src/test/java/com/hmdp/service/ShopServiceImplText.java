package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * 这个测试类用来模拟
 * 活动前向后端存入缓存
 */
@SpringBootTest
public class ShopServiceImplText {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    //TODO 这里可能有空指针异常，换用import org.junit.jupiter.api.Test;就可以解决
    @Test
    public void saveShopText()throws InterruptedException{
        //封装工具类前的代码
        //shopService.saveShop2Redis(1L,10L);
        //封装后
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L,shop,10L, TimeUnit.SECONDS);

    }
}

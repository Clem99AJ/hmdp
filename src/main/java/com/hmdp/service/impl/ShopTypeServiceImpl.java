package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    //课后练习
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryByList() {
        //TODO 注意查询的内容有多个，选择合适类型
        //TODO 1、查询redis中是否有店铺类型数据
        String lockShop = stringRedisTemplate.opsForValue().get(LOCK_SHOP_KEY);
        //TODO 2、存在，转化为list返回
        if(StrUtil.isNotBlank(lockShop)){
            List<ShopType> typeList = JSONUtil.toList(lockShop, ShopType.class);
            return Result.ok(typeList);
        }
        //TODO 3、不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //TODO 4、数据库也不存在，报错
        if(typeList==null){
            return Result.fail("店铺类型不存在");
        }
        //TODO 5、数据库存在，保存到redis
        String typeJson = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(LOCK_SHOP_KEY,typeJson);
        //TODO 6、返回list
        return Result.ok(typeJson);
    }
}

package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Slf4j//用于日志
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1、校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2、如果不符合
            return Result.fail("手机号格式错误");
        }
        //3、符合，生成验证码(直接用工具包完成)
        String code = RandomUtil.randomNumbers(6);
        //4、保存验证码到session(原本的方式，舍弃)
        //session.setAttribute("code",code);
        //4、验证码保存到Redis中
        // (这里用String类型，在key值上得加点前缀，因为redis是直接存在一起的，用于区别开其他业务的key
        // 并且这里要设置一个有效期，因为验证码是有有效日期的,防止有人乱点，占满redis)
        //stringRedisTemplate.opsForValue().set("login:code:"+phone,code,2, TimeUnit.MINUTES);
        //在写这些前缀，时间时，最好定义成常量，写成一个工具包，防止以后写错
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5、发送验证码(这里是用日志模拟验证，真实开发时，会调用相应的服务)
        log.debug("发送短信验证码成功，验证码：{}",code);
        //返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1、验证手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //手机号不符合
            return  Result.fail("手机号格式错误");
        }
        //2、验证验证码
        //Object cacheCode = session.getAttribute("code");//这是原本的从session获取，舍弃
        //TODO 2、验证码校验，从redis获取
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        //3、不一致报错
        if(cacheCode==null||!cacheCode.toString().equals(code)){
            return Result.fail("验证码错误");
        }
        //4、一致，根据手机号查询用户  select * from tb_user where phone = ?(这里用了Mybatis plus,简化了用法，不用像以前一样麻烦)
        User user = query().eq("phone", phone).one();//eq判断

        //System.out.println(user);
        //5、判断用户是否存在
        if(user == null){
            //6、不存在，创建新用户保存
            user = creatUserWithPhone(phone);
        }
        //7、存在,保存用户信息到session中，并返回,舍弃
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //TODO 7.保存用户到redis
        //TODO 7.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //TODO 7.2 将User对象转为Hash存储（先转UserDTO,在把UserDTO转化为HashMap,还是用hutool简单完成）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //TODO 这里会有一个类型错误，redis需要的是String类型，但是userDTO里有一个long型，有两种办法转化，手动或beanToMap的自定义方法
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((filedName,filedValue) ->filedValue.toString())
        );
        //TODO 7.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //TODO 7.4 设置有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //TODO 返回token
        return Result.ok(token);
    }

    private User creatUserWithPhone(String phone) {
        //1、创建用户
        User user = new User();
        user.setPhone(phone);
        //这里用了写好的工具包utils
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //保存用户(保存同样用了Mybatis plus)
        save(user);
        return user;
    }
}

package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    RedisWorker redisWorker;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    RedissonClient redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
//        因为要写不止一行，所以放到代码块
        SECKILL_SCRIPT = new DefaultRedisScript<>();
//        去类路径下找
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
//        设置返回值类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                        SECKILL_SCRIPT,
                Collections.emptyList(),
        voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2.为0 ，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.6 放入消息队列
        rabbitTemplate.convertAndSend("voucher.que", voucherOrder);
        // 2.6.放入阻塞队列
//        orderTasks.add(voucherOrder);
        // 3.返回订单id
        return Result.ok(orderId);
    }
//    @Override
////    库存扣减 & 订单新增，两张表，属于一个事务
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//        // 3.判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 已经结束
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足！");
//        }
//       // 5. 一人一单
//        Long userId = UserHolder.getUser().getId();
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//// 测试，时间弄1200秒
//        // 尝试获取锁，无参用的是默认值。 -1，也就是不等待 。30s自动释放
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            //获取锁失败
//            return Result.fail("不允许重复下单");
//        }
//// 获取锁成功
//        try {
////            得到  IVoucherOrderService  的代理对象，通过代理对象调用方法，事务才不会失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放
//            lock.unlock();
//        }
//////        6. 扣减库存
////        boolean success = seckillVoucherService.update()
////                .setSql("stock = stock - 1") // set stock = stock - 1
////                .eq("voucher_id", voucherId) // where voucher_id = ?
////                .update();
////        if(!success){
////            // 库存不足
////            return Result.fail("库存不足！");
////        }
//
////        7. 创建订单
////        VoucherOrder voucherOrder = new VoucherOrder();
////        // 7.1.订单id
////        long orderId = redisWorker.nextId("order");
////        voucherOrder.setId(orderId);
////        // 7.2.用户id
////        voucherOrder.setUserId(userId);
////        // 7.3.代金券id
////        voucherOrder.setVoucherId(voucherId);
//////        写入数据库
////        save(voucherOrder);
////
////        // 8.返回订单id
////        return Result.ok(orderId);
//    }

    @Transactional
    public  Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        synchronized(userId.toString().intern()){
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {   
                // 扣减失败
                return Result.fail("库存不足！");
            }

            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id
            long orderId = redisWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);
        }
    }
}

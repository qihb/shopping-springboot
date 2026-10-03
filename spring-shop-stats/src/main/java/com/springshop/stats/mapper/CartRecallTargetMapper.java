package com.springshop.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.stats.entity.CartRecallTarget;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 加购未买待召回人群 Mapper
 */
@Mapper
public interface CartRecallTargetMapper extends BaseMapper<CartRecallTarget> {

    /**
     * 批量插入待召回条目（XML foreach 拼多值 INSERT，避免逐行往返）
     */
    int insertBatch(@Param("list") List<CartRecallTarget> list);

    /**
     * 清空「待处理」条目：跑批前重建池子用
     *
     * <p>只删 {@code status = 0}（待处理）的行，已发券/已转化/已失效的记录必须保留，
     * 否则会丢掉「这张券发给谁、有没有转化」的痕迹，活动无法复盘。
     */
    int deletePending();
}

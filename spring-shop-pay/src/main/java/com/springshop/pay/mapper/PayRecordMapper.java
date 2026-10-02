package com.springshop.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.pay.entity.PayRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付记录 Mapper
 */
@Mapper
public interface PayRecordMapper extends BaseMapper<PayRecord> {
}

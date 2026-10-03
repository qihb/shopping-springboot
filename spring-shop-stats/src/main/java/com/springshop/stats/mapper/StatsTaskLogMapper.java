package com.springshop.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.stats.entity.StatsTaskLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 统计任务执行日志 Mapper
 */
@Mapper
public interface StatsTaskLogMapper extends BaseMapper<StatsTaskLog> {
}

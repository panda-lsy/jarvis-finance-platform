package com.jarvis.research.service;

/**
 * Legacy placeholder.
 *
 * 旧版 GoldPriceService 曾直接访问腾讯/京东行情，形成与 MarketDataService 并行的第二套行情通道。
 * 当前生产架构已统一为：MarketDataService/JdGoldService 定时采集 -> Java 数据库 -> API/模拟盘读取。
 * 保留此文件仅避免历史路径/IDE 缓存造成误解，不再声明 Spring Bean。
 */

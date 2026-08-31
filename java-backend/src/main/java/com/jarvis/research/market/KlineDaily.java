package com.jarvis.research.market;

import jakarta.persistence.*;

/**
 * 日K线: 每日抓取一次并持久化
 * 前端查询走此表, 避免频繁请求外部数据源
 */
@Entity
@Table(name = "kline_daily", indexes = {
        @Index(name = "idx_kline_market_date", columnList = "market,date", unique = true)
})
public class KlineDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标的: gold_etf / london_gold */
    @Column(nullable = false, length = 32)
    private String market;

    /** 日期 yyyy-MM-dd */
    @Column(nullable = false, length = 16)
    private String date;

    @Column(nullable = false)
    private Double open;

    @Column(nullable = false)
    private Double close;

    @Column(nullable = false)
    private Double high;

    @Column(nullable = false)
    private Double low;

    private Double volume;

    public KlineDaily() {}

    public KlineDaily(String market, String date, Double open, Double close, Double high, Double low, Double volume) {
        this.market = market;
        this.date = date;
        this.open = open;
        this.close = close;
        this.high = high;
        this.low = low;
        this.volume = volume;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public Double getOpen() { return open; }
    public void setOpen(Double open) { this.open = open; }
    public Double getClose() { return close; }
    public void setClose(Double close) { this.close = close; }
    public Double getHigh() { return high; }
    public void setHigh(Double high) { this.high = high; }
    public Double getLow() { return low; }
    public void setLow(Double low) { this.low = low; }
    public Double getVolume() { return volume; }
    public void setVolume(Double volume) { this.volume = volume; }
}

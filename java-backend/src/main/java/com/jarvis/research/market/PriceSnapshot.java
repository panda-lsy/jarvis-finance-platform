package com.jarvis.research.market;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 价格快照: 实时存取黄金ETF/伦敦金价格
 * 每次实时抓取后写入, 用于生成分钟K线
 */
@Entity
@Table(name = "price_snapshot", indexes = {
        @Index(name = "idx_snap_market_ts", columnList = "market,ts")
})
public class PriceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标的: gold_etf / london_gold */
    @Column(nullable = false, length = 32)
    private String market;

    /** 现价 */
    @Column(nullable = false)
    private Double price;

    /** 涨跌额 */
    private Double change;

    /** 涨跌幅% */
    private Double changePct;

    /** 昨收 */
    private Double prevClose;

    /** 今开 */
    private Double open;

    /** 最高 */
    private Double high;

    /** 最低 */
    private Double low;

    /** 时间戳 */
    @Column(nullable = false)
    private LocalDateTime ts;

    public PriceSnapshot() {}

    public PriceSnapshot(String market, Double price, Double change, Double changePct,
                         Double prevClose, Double open, Double high, Double low, LocalDateTime ts) {
        this.market = market;
        this.price = price;
        this.change = change;
        this.changePct = changePct;
        this.prevClose = prevClose;
        this.open = open;
        this.high = high;
        this.low = low;
        this.ts = ts;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public Double getChange() { return change; }
    public void setChange(Double change) { this.change = change; }
    public Double getChangePct() { return changePct; }
    public void setChangePct(Double changePct) { this.changePct = changePct; }
    public Double getPrevClose() { return prevClose; }
    public void setPrevClose(Double prevClose) { this.prevClose = prevClose; }
    public Double getOpen() { return open; }
    public void setOpen(Double open) { this.open = open; }
    public Double getHigh() { return high; }
    public void setHigh(Double high) { this.high = high; }
    public Double getLow() { return low; }
    public void setLow(Double low) { this.low = low; }
    public LocalDateTime getTs() { return ts; }
    public void setTs(LocalDateTime ts) { this.ts = ts; }
}

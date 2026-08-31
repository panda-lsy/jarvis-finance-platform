/**
 * 行情页 - 最新价格 + 历史K线走势
 */
import React, { useState, useEffect } from 'react'
import {
  View, Text, StyleSheet, ScrollView, RefreshControl, ActivityIndicator,
} from 'react-native'
import { LineChart } from 'react-native-chart-kit'
import { Dimensions } from 'react-native'
import { api } from '../api/client'

const screenWidth = Dimensions.get('window').width

export default function MarketScreen() {
  const [prices, setPrices] = useState(null)
  const [kline, setKline] = useState([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)

  async function load() {
    try {
      const [p, k] = await Promise.all([
        api.prices(),
        api.kline({ market: 'gold_etf', limit: 60 }),
      ])
      setPrices(p.prices.gold_etf || null)
      setKline(k.data || [])
    } catch (e) {
      setPrices(null)
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }

  useEffect(() => { load() }, [])

  const realtime = prices && prices.realtime
  const closes = kline.map((x) => x.close)
  const dates = kline.map((x) => x.date.slice(5))

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={{ padding: 16, paddingBottom: 40 }}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); load() }} />}
    >
      {loading ? (
        <ActivityIndicator size="large" color="#4da8ff" style={{ marginTop: 80 }} />
      ) : (
        <>
          {/* 价格卡 */}
          <View style={styles.priceCard}>
            <Text style={styles.label}>{realtime ? realtime.name : '后端未连接'}</Text>
            {realtime ? (
              <>
                <Text style={styles.price}>{realtime.price}</Text>
                <Text style={realtime.change_pct >= 0 ? styles.pos : styles.neg}>
                  {realtime.change_pct >= 0 ? '+' : ''}{realtime.change_pct}%  ({realtime.change >= 0 ? '+' : ''}{realtime.change})
                </Text>
              </>
            ) : (
              <Text style={styles.hint}>请在 client.js 配置后端地址</Text>
            )}
          </View>

          {/* K线走势 */}
          {closes.length > 0 ? (
            <View style={styles.card}>
              <Text style={styles.sectionTitle}>近60日收盘价走势</Text>
              <LineChart
                data={{
                  labels: dates.filter((_, i) => i % 10 === 0),
                  datasets: [{ data: closes, color: () => '#4da8ff' }],
                }}
                width={screenWidth - 32}
                height={220}
                chartConfig={{
                  backgroundGradientFrom: '#121a2d',
                  backgroundGradientTo: '#0f1626',
                  decimalPlaces: 2,
                  color: () => '#4da8ff',
                  labelColor: () => '#8ba0c8',
                }}
                bezier
                style={{ borderRadius: 8 }}
              />
              <Text style={styles.hint}>区间 {kline[0]?.date} ~ {kline[kline.length - 1]?.date}</Text>
            </View>
          ) : null}
        </>
      )}
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0b1020' },
  priceCard: {
    backgroundColor: '#121a2d', borderRadius: 12, padding: 20,
    borderWidth: 1, borderColor: '#243453', marginBottom: 16,
  },
  label: { color: '#8ba0c8', fontSize: 14 },
  price: { color: '#4da8ff', fontSize: 40, fontWeight: '700', marginVertical: 6 },
  pos: { color: '#27c46b', fontSize: 16 },
  neg: { color: '#ef5350', fontSize: 16 },
  card: {
    backgroundColor: '#121a2d', borderRadius: 12, padding: 16,
    borderWidth: 1, borderColor: '#243453',
  },
  sectionTitle: { color: '#e9effb', fontSize: 16, fontWeight: '600', marginBottom: 12 },
  hint: { color: '#8ba0c8', fontSize: 12, marginTop: 10 },
})

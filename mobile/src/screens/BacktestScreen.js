/**
 * 回测页 - 双均线策略回测
 */
import React, { useState } from 'react'
import {
  View, Text, StyleSheet, ScrollView, TextInput, TouchableOpacity,
  ActivityIndicator, FlatList,
} from 'react-native'
import { api } from '../api/client'

export default function BacktestScreen() {
  const [shortMa, setShortMa] = useState('5')
  const [longMa, setLongMa] = useState('20')
  const [cash, setCash] = useState('100000')
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function run() {
    setLoading(true)
    setError('')
    setResult(null)
    try {
      const d = await api.backtest({
        market: 'gold_etf',
        short_ma: Number(shortMa),
        long_ma: Number(longMa),
        initial_cash: Number(cash),
        limit: 120,
      })
      setResult(d)
    } catch (e) {
      setError('回测失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }

  const Item = ({ label, value, color }) => (
    <View style={styles.metric}>
      <Text style={styles.mLabel}>{label}</Text>
      <Text style={[styles.mValue, { color }]}>{value}</Text>
    </View>
  )

  return (
    <ScrollView style={styles.container} contentContainerStyle={{ padding: 16, paddingBottom: 40 }}>
      <Text style={styles.title}>双均线策略回测</Text>

      {/* 参数输入 */}
      <View style={styles.form}>
        <TextInput style={styles.input} keyboardType="numeric" value={shortMa}
          onChangeText={setShortMa} placeholder="短期均线" placeholderTextColor="#555" />
        <TextInput style={styles.input} keyboardType="numeric" value={longMa}
          onChangeText={setLongMa} placeholder="长期均线" placeholderTextColor="#555" />
        <TextInput style={styles.input} keyboardType="numeric" value={cash}
          onChangeText={setCash} placeholder="本金" placeholderTextColor="#555" />
        <TouchableOpacity style={styles.btn} onPress={run} disabled={loading}>
          <Text style={styles.btnText}>{loading ? '计算中...' : '运行回测'}</Text>
        </TouchableOpacity>
      </View>

      {error ? <Text style={styles.error}>{error}</Text> : null}

      {loading ? <ActivityIndicator color="#4da8ff" style={{ marginTop: 40 }} /> : null}

      {result && !result.error && (
        <>
          <View style={styles.metricsGrid}>
            <Item label="期末资金" value={result.final_equity?.toLocaleString()} color="#4da8ff" />
            <Item label="总收益率" value={result.total_return_pct + '%'} color={result.total_return_pct >= 0 ? '#27c46b' : '#ef5350'} />
            <Item label="年化收益" value={result.annual_return_pct + '%'} color={result.annual_return_pct >= 0 ? '#27c46b' : '#ef5350'} />
            <Item label="买入持有" value={result.buy_hold_return_pct + '%'} color={result.buy_hold_return_pct >= 0 ? '#27c46b' : '#ef5350'} />
            <Item label="最大回撤" value={result.max_drawdown_pct + '%'} color="#ef5350" />
            <Item label="交易次数" value={String(result.num_trades)} color="#e9effb" />
          </View>

          {result.trades.length > 0 && (
            <>
              <Text style={styles.sectionTitle}>近期交易 ({result.trades.length}笔)</Text>
              <View style={styles.card}>
                {result.trades.slice(-6).map((t, i) => (
                  <View key={i} style={styles.tradeRow}>
                    <Text style={styles.tradeDate}>{t.date}</Text>
                    <Text style={[styles.tradeType, { color: t.type === 'BUY' ? '#27c46b' : '#ef5350' }]}>{t.type}</Text>
                    <Text style={styles.tradePrice}>@{t.price}</Text>
                  </View>
                ))}
              </View>
            </>
          )}
        </>
      )}
      {result && result.error ? <Text style={styles.error}>{result.error}</Text> : null}
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0b1020' },
  title: { color: '#e9effb', fontSize: 18, fontWeight: '700', marginBottom: 14 },
  form: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginBottom: 16 },
  input: {
    flex: 1, minWidth: 90, backgroundColor: '#121a2d', color: '#e9effb',
    borderWidth: 1, borderColor: '#243453', borderRadius: 8, padding: 10,
  },
  btn: { backgroundColor: '#4da8ff', borderRadius: 8, padding: 10, justifyContent: 'center' },
  btnText: { color: '#fff', fontWeight: '600' },
  error: { color: '#ef5350', marginVertical: 12 },
  metricsGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  metric: { width: '48%', backgroundColor: '#121a2d', borderRadius: 8, padding: 14, borderWidth: 1, borderColor: '#1a2540' },
  mLabel: { color: '#8ba0c8', fontSize: 12 },
  mValue: { fontSize: 20, fontWeight: '700', marginTop: 4 },
  sectionTitle: { color: '#e9effb', fontSize: 16, fontWeight: '600', marginTop: 20, marginBottom: 10 },
  card: { backgroundColor: '#121a2d', borderRadius: 8, padding: 12, borderWidth: 1, borderColor: '#243453' },
  tradeRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#1a2540' },
  tradeDate: { color: '#8ba0c8' },
  tradeType: { fontWeight: '700' },
  tradePrice: { color: '#e9effb' },
})

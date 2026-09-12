import { useEcharts } from './useEcharts'

const PRICE_UP = '#ef5350'
const PRICE_DOWN = '#27c46b'
const AXIS = '#8f9498'
const GRID = '#24272b'
const LINE = '#34383d'
const TOOLTIP_BG = '#17191b'
const TOOLTIP_BORDER = '#35383d'

function compactDate(value) {
  const text = String(value || '')
  if (text.length >= 16) return text.slice(5, 16)
  return text.length >= 10 ? text.slice(5, 10) : text
}

export function useMarketChart() {
  const chart = useEcharts()

  async function renderCandles(data, options = {}) {
    const rows = Array.isArray(data) ? data : []
    if (!rows.length) {
      chart.clear()
      return null
    }

    const {
      withVolume = false,
      visibleCount = 60,
      overlays = [],
      dateFormatter = compactDate,
      riseColor = PRICE_UP,
      fallColor = PRICE_DOWN,
      showLegend = true,
      showSlider = true,
      xLabelsOnVolume = false,
      gridColor = GRID,
      axisColor = AXIS,
    } = options

    const dates = rows.map(item => item.date)
    const ohlc = rows.map(item => [item.open, item.close, item.low, item.high])
    const volumes = rows.map((item, index) => [
      index,
      Number(item.volume || 0),
      Number(item.close) >= Number(item.open) ? 1 : -1,
    ])
    const lastIndex = Math.max(0, dates.length - 1)
    const startIndex = Math.max(0, lastIndex - Math.max(1, visibleCount - 1))
    const zoomXAxis = withVolume ? [0, 1] : 0

    const mainXAxis = {
      type: 'category',
      data: dates,
      boundaryGap: true,
      axisTick: { show: false },
      axisLabel: {
        show: !withVolume || !xLabelsOnVolume,
        color: axisColor,
        fontSize: 10,
        hideOverlap: true,
        formatter: dateFormatter,
      },
      axisLine: { lineStyle: { color: LINE } },
    }
    const mainYAxis = {
      scale: true,
      position: 'right',
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: axisColor, fontSize: 10 },
      splitLine: { lineStyle: { color: gridColor } },
    }

    const overlaySeries = overlays.map(item => ({
      name: item.name,
      type: 'line',
      data: item.values || rows.map(row => row[item.key] ?? '-'),
      showSymbol: false,
      smooth: false,
      lineStyle: { color: item.color, width: item.width ?? 1.2, type: item.type || 'solid' },
    }))

    const legendData = ['K线']
    if (withVolume) legendData.push('成交量')
    legendData.push(...overlays.map(item => item.name))

    const dataZoom = [
      { type: 'inside', xAxisIndex: zoomXAxis, startValue: startIndex, endValue: lastIndex, minValueSpan: 8 },
    ]
    if (showSlider) {
      dataZoom.push({
        type: 'slider',
        xAxisIndex: zoomXAxis,
        startValue: startIndex,
        endValue: lastIndex,
        minValueSpan: 8,
        height: 14,
        bottom: 5,
        borderColor: '#30343a',
        backgroundColor: '#151719',
        fillerColor: 'rgba(201,166,95,.16)',
        handleSize: 10,
        textStyle: { color: '#73787d', fontSize: 9 },
      })
    }

    return chart.setOption({
      animation: false,
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross', snap: true },
        backgroundColor: TOOLTIP_BG,
        borderColor: TOOLTIP_BORDER,
        textStyle: { color: '#f1efe8', fontSize: 11 },
      },
      legend: {
        show: showLegend,
        top: 2,
        left: 10,
        itemWidth: 12,
        itemHeight: 6,
        textStyle: { color: axisColor, fontSize: 10 },
        data: legendData,
      },
      grid: withVolume
        ? [
            { left: 12, right: 64, top: showLegend ? 30 : 12, height: xLabelsOnVolume ? '66%' : '61%' },
            { left: 12, right: 64, top: xLabelsOnVolume ? '79%' : '76%', height: xLabelsOnVolume ? '12%' : '11%' },
          ]
        : { left: 12, right: 64, top: showLegend ? 30 : 12, bottom: showSlider ? 46 : 24 },
      xAxis: withVolume
        ? [
            mainXAxis,
            {
              type: 'category',
              gridIndex: 1,
              data: dates,
              boundaryGap: true,
              axisLabel: {
                show: xLabelsOnVolume,
                color: axisColor,
                fontSize: 10,
                hideOverlap: true,
                formatter: dateFormatter,
              },
              axisTick: { show: false },
              axisLine: { show: xLabelsOnVolume, lineStyle: { color: LINE } },
            },
          ]
        : mainXAxis,
      yAxis: withVolume
        ? [
            mainYAxis,
            {
              gridIndex: 1,
              position: 'right',
              axisLabel: { show: false },
              axisTick: { show: false },
              axisLine: { show: false },
              splitLine: { show: false },
            },
          ]
        : mainYAxis,
      dataZoom,
      series: [
        {
          name: 'K线',
          type: 'candlestick',
          data: ohlc,
          itemStyle: { color: riseColor, color0: fallColor, borderColor: riseColor, borderColor0: fallColor },
        },
        ...(withVolume ? [{
          name: '成交量',
          type: 'bar',
          xAxisIndex: 1,
          yAxisIndex: 1,
          data: volumes,
          itemStyle: { color: params => params.data[2] > 0 ? riseColor : fallColor },
        }] : []),
        ...overlaySeries,
      ],
    })
  }

  return {
    elementRef: chart.elementRef,
    renderCandles,
    resize: chart.resize,
    clear: chart.clear,
    dispose: chart.dispose,
    getChart: chart.getChart,
  }
}

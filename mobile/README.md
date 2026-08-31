# 贾维斯黄金 - React Native 移动端 (Expo)

移动端视图：最新行情 + 双均线回测。通过本机后端 (FastAPI :8100) 获取数据。

## 启动

```bash
cd mobile
npm install
npx expo start        # 扫码用 Expo Go 打开
npx expo start --android   # 安卓
npx expo start --ios       # iOS
```

## 后端地址配置

编辑 `src/api/client.js`：

- 真机：改成你电脑的局域网 IP，如 `http://192.168.1.100:8100`
- 安卓模拟器：`http://10.0.2.2:8100`
- iOS 模拟器：`http://127.0.0.1:8100`

## 页面

| Tab | 说明 |
|-----|------|
| 行情 | 最新价 + 近60日收盘价走势图 (react-native-chart-kit) |
| 回测 | 双均线策略回测，展示收益/回撤/交易记录 |

## 依赖

- expo ~51, react-native 0.74
- @react-navigation/bottom-tabs
- react-native-chart-kit + react-native-svg

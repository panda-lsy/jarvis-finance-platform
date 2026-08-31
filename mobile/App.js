/**
 * 贾维斯黄金 - 移动端入口
 */
import 'react-native-gesture-handler'
import React from 'react'
import { NavigationContainer } from '@react-navigation/native'
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs'
import { StatusBar } from 'expo-status-bar'
import { SafeAreaProvider } from 'react-native-safe-area-context'

import MarketScreen from './src/screens/MarketScreen'
import BacktestScreen from './src/screens/BacktestScreen'

const Tab = createBottomTabNavigator()

export default function App() {
  return (
    <SafeAreaProvider>
      <NavigationContainer>
        <Tab.Navigator
          screenOptions={{
            headerStyle: { backgroundColor: '#121a2d' },
            headerTintColor: '#e9effb',
            tabBarStyle: { backgroundColor: '#121a2d', borderTopColor: '#243453' },
            tabBarActiveTintColor: '#4da8ff',
            tabBarInactiveTintColor: '#8ba0c8',
          }}
        >
          <Tab.Screen name="行情" component={MarketScreen} />
          <Tab.Screen name="回测" component={BacktestScreen} />
        </Tab.Navigator>
      </NavigationContainer>
      <StatusBar style="light" />
    </SafeAreaProvider>
  )
}

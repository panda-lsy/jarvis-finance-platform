// Hero terminal: product-style autoplay simulated click demo.
const demoTerminal = document.querySelector('.terminal:not(.video-demo-active)')
if (demoTerminal && !motionReduced) {
  const cursor = demoTerminal.querySelector('[data-demo-cursor]')
  const targetRing = demoTerminal.querySelector('[data-demo-target-ring]')
  const pane = demoTerminal.querySelector('[data-demo-pane]')
  const areaPath = demoTerminal.querySelector('[data-demo-area]')
  const linePath = demoTerminal.querySelector('[data-demo-line]')
  const volumeRects = [...demoTerminal.querySelectorAll('[data-demo-volume] rect')]
  const assets = [...demoTerminal.querySelectorAll('[data-symbol]')]
  const ranges = [...demoTerminal.querySelectorAll('[data-range-key]')]
  const aiSignal = demoTerminal.querySelector('[data-demo-ai-signal]')
  const demoEls = {
    title: demoTerminal.querySelector('[data-demo-symbol-title]'), sub: demoTerminal.querySelector('[data-demo-symbol-sub]'),
    price: demoTerminal.querySelector('[data-demo-price-big]'), change: demoTerminal.querySelector('[data-demo-price-change]'),
    open: demoTerminal.querySelector('[data-demo-open]'), high: demoTerminal.querySelector('[data-demo-high]'),
    low: demoTerminal.querySelector('[data-demo-low]'), vol: demoTerminal.querySelector('[data-demo-vol]'),
    signalTitle: demoTerminal.querySelector('[data-demo-signal-title]'), signalCopy: demoTerminal.querySelector('[data-demo-signal-copy]'),
  }
  const states = {
    'XAUUSD|1D': {symbol:'XAUUSD',name:'Gold Spot · USD',price:'3,635.20',change:'+24.63 · +0.68%',tone:'pos',open:'3,610.57',high:'3,641.08',low:'3,598.36',vol:'18.4%',signalTitle:'Moderately Bullish',signalCopy:'美元走弱与实际利率回落共同支撑黄金。短期动量仍偏强，但接近局部阻力区。',line:'M0,280 C50,270 70,285 110,248 C160,203 190,225 230,210 C270,195 295,230 335,190 C370,155 392,173 430,130 C470,90 500,115 545,98 C590,81 605,124 650,90 C705,46 742,72 800,32',area:'M0,280 C50,270 70,285 110,248 C160,203 190,225 230,210 C270,195 295,230 335,190 C370,155 392,173 430,130 C470,90 500,115 545,98 C590,81 605,124 650,90 C705,46 742,72 800,32 L800,360 L0,360Z',volume:[15,24,19,30,23,38,27,33,43,25,36,48,27,40,33,54,46,34,49,58,44,55,37,52,63,40,57,68,49,75]},
    'XAUUSD|5D': {symbol:'XAUUSD',name:'Gold Spot · USD',price:'3,635.20',change:'+46.11 · +1.29%',tone:'pos',open:'3,588.42',high:'3,644.30',low:'3,571.15',vol:'19.8%',signalTitle:'Bullish Continuation',signalCopy:'多日趋势仍保持上行结构，AI 将美元方向与避险买盘视作主要支撑。',line:'M0,295 C52,300 94,282 136,244 C176,208 208,222 252,184 C292,148 335,175 372,143 C410,111 446,132 491,96 C542,54 577,90 625,70 C684,46 732,67 800,26',area:'M0,295 C52,300 94,282 136,244 C176,208 208,222 252,184 C292,148 335,175 372,143 C410,111 446,132 491,96 C542,54 577,90 625,70 C684,46 732,67 800,26 L800,360 L0,360Z',volume:[12,16,18,19,22,30,34,31,29,35,39,44,40,42,46,52,55,51,57,59,61,60,57,66,69,63,71,74,78,82]},
    'NVDA|1D': {symbol:'NVDA',name:'NVIDIA · NASDAQ',price:'183.42',change:'+4.20 · +2.36%',tone:'pos',open:'178.93',high:'184.08',low:'177.52',vol:'32.6%',signalTitle:'Momentum Strong',signalCopy:'AI 基础设施主题维持强势，资金继续偏好龙头科技资产。',line:'M0,300 C55,286 95,292 132,257 C174,215 211,236 248,190 C292,137 334,169 370,135 C412,95 452,111 494,88 C545,54 585,78 628,63 C688,42 730,59 800,18',area:'M0,300 C55,286 95,292 132,257 C174,215 211,236 248,190 C292,137 334,169 370,135 C412,95 452,111 494,88 C545,54 585,78 628,63 C688,42 730,59 800,18 L800,360 L0,360Z',volume:[24,26,29,35,37,42,45,49,55,58,63,66,70,62,59,72,76,80,77,82,86,88,90,85,83,92,96,102,104,110]},
    'NVDA|1M': {symbol:'NVDA',name:'NVIDIA · NASDAQ',price:'183.42',change:'+12.36 · +7.23%',tone:'pos',open:'170.66',high:'184.08',low:'168.44',vol:'36.2%',signalTitle:'Trend Extension',signalCopy:'中期曲线继续抬升，回测与实时行情都显示动量仍在延续。',line:'M0,322 C48,315 76,292 118,262 C165,228 201,235 244,198 C292,157 334,160 380,121 C425,83 463,100 510,68 C566,30 609,55 653,41 C705,26 748,35 800,12',area:'M0,322 C48,315 76,292 118,262 C165,228 201,235 244,198 C292,157 334,160 380,121 C425,83 463,100 510,68 C566,30 609,55 653,41 C705,26 748,35 800,12 L800,360 L0,360Z',volume:[20,23,26,30,33,38,42,44,47,52,58,60,63,67,70,73,77,81,84,88,90,94,97,93,99,104,108,112,118,124]},
    'BTCUSD|1D': {symbol:'BTCUSD',name:'Bitcoin · USD',price:'112,840',change:'-472 · -0.42%',tone:'neg',open:'113,402',high:'114,120',low:'112,180',vol:'41.7%',signalTitle:'Volatility Elevated',signalCopy:'短线资金分歧增强，波动率上升，AI 建议降低追涨仓位并关注风险区间。',line:'M0,212 C38,198 86,190 125,206 C170,224 203,219 247,242 C292,266 333,241 377,259 C421,276 463,251 506,264 C555,279 598,267 641,283 C697,305 741,298 800,292',area:'M0,212 C38,198 86,190 125,206 C170,224 203,219 247,242 C292,266 333,241 377,259 C421,276 463,251 506,264 C555,279 598,267 641,283 C697,305 741,298 800,292 L800,360 L0,360Z',volume:[18,24,16,32,29,34,27,41,38,43,30,46,44,51,39,54,58,46,62,48,57,44,61,53,66,50,59,71,57,69]},
    'BTCUSD|1Y': {symbol:'BTCUSD',name:'Bitcoin · USD',price:'112,840',change:'+18,420 · +19.51%',tone:'pos',open:'94,180',high:'114,120',low:'91,540',vol:'56.3%',signalTitle:'Macro Beta High',signalCopy:'长周期仍保留上行斜率，但路径更加陡峭，回撤与风险暴露显著高于黄金。',line:'M0,326 C64,305 102,318 150,278 C194,242 231,251 278,212 C326,173 370,189 416,141 C470,85 518,113 565,77 C612,42 660,66 706,38 C742,18 771,22 800,8',area:'M0,326 C64,305 102,318 150,278 C194,242 231,251 278,212 C326,173 370,189 416,141 C470,85 518,113 565,77 C612,42 660,66 706,38 C742,18 771,22 800,8 L800,360 L0,360Z',volume:[22,26,28,32,35,39,46,43,49,54,59,62,65,70,74,79,76,83,88,92,96,102,98,107,111,118,115,124,129,136]},
  }
  const initial='XAUUSD|1D'
  const sleep=(ms)=>new Promise((resolve)=>window.setTimeout(resolve,ms))
  const updateState=(key)=>{const s=states[key];if(!s)return;demoEls.title.textContent=s.symbol;demoEls.sub.textContent=s.name;demoEls.price.textContent=s.price;demoEls.change.textContent=s.change;demoEls.change.classList.remove('pos','neg');demoEls.change.classList.add(s.tone);demoEls.open.textContent=s.open;demoEls.high.textContent=s.high;demoEls.low.textContent=s.low;demoEls.vol.textContent=s.vol;demoEls.signalTitle.textContent=s.signalTitle;demoEls.signalCopy.textContent=s.signalCopy;pane.classList.add('is-demo-switching');linePath.setAttribute('d',s.line);areaPath.setAttribute('d',s.area);s.volume.forEach((h,i)=>{const r=volumeRects[i];if(!r)return;r.setAttribute('height',String(h));r.setAttribute('y',String(353-h))});const [symbol,range]=key.split('|');assets.forEach((a)=>a.classList.toggle('active',a.dataset.symbol===symbol));ranges.forEach((r)=>r.classList.toggle('is-active',r.dataset.rangeKey===range));window.setTimeout(()=>pane.classList.remove('is-demo-switching'),240)}
  const pulseTarget=(el)=>{if(!el)return;el.classList.remove('demo-click');void el.offsetWidth;el.classList.add('demo-click');window.setTimeout(()=>el.classList.remove('demo-click'),620)}
  const setFocus=(kind)=>{demoTerminal.classList.remove('demo-focus-watch','demo-focus-chart','demo-focus-signal');if(kind)demoTerminal.classList.add(`demo-focus-${kind}`)}
  const positionRing=(el)=>{if(!targetRing||!el)return;const host=demoTerminal.getBoundingClientRect();const r=el.getBoundingClientRect();targetRing.style.left=`${r.left-host.left-4}px`;targetRing.style.top=`${r.top-host.top-4}px`;targetRing.style.width=`${r.width+8}px`;targetRing.style.height=`${r.height+8}px`;targetRing.classList.add('is-visible')}
  const hideRing=()=>targetRing?.classList.remove('is-visible')
  const moveCursorTo=async(el,focus)=>{if(!el||!cursor)return;setFocus(focus);positionRing(el);const host=demoTerminal.getBoundingClientRect();const r=el.getBoundingClientRect();const x=r.left-host.left+Math.min(r.width*.72,r.width-12);const y=r.top-host.top+r.height*.5;cursor.style.opacity='1';cursor.style.transform=`translate(${x}px,${y}px)`;await sleep(760);cursor.classList.add('is-pressing');pulseTarget(el);await sleep(110);cursor.classList.remove('is-pressing');await sleep(170);hideRing()}
  const steps=[
    {selector:'[data-symbol="NVDA"]',key:'NVDA|1D',focus:'watch',hold:1050},
    {selector:'[data-range-key="1M"]',key:'NVDA|1M',focus:'chart',hold:1250},
    {selector:'[data-demo-ai-signal]',focus:'signal',hold:1350},
    {selector:'[data-symbol="BTCUSD"]',key:'BTCUSD|1D',focus:'watch',hold:1050},
    {selector:'[data-range-key="1Y"]',key:'BTCUSD|1Y',focus:'chart',hold:1250},
    {selector:'[data-demo-ai-signal]',focus:'signal',hold:1350},
    {selector:'[data-symbol="XAUUSD"]',key:'XAUUSD|5D',focus:'watch',hold:950},
    {selector:'[data-range-key="1D"]',key:'XAUUSD|1D',focus:'chart',hold:1400},
  ]
  let demoVisible=false, demoRunning=false, runToken=0
  const runLoop=async(token)=>{if(demoRunning)return;demoRunning=true;await sleep(700);updateState(initial);while(demoVisible&&token===runToken){for(const step of steps){if(!demoVisible||token!==runToken)break;const target=demoTerminal.querySelector(step.selector);await moveCursorTo(target,step.focus);if(step.key)updateState(step.key);await sleep(step.hold)}if(demoVisible&&token===runToken){setFocus(null);await sleep(900)}}cursor.style.opacity='0';hideRing();setFocus(null);demoRunning=false}
  const demoObserver=new IntersectionObserver((entries)=>{entries.forEach((entry)=>{demoVisible=entry.isIntersecting&&entry.intersectionRatio>.28;if(demoVisible){runToken+=1;runLoop(runToken)}else{runToken+=1;cursor.style.opacity='0';hideRing();setFocus(null)}})},{threshold:[0,.28,.6]})
  demoObserver.observe(demoTerminal)
}

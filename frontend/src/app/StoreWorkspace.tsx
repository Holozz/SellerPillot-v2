import { useState } from 'react'
import type { Store } from '../shared/api'
import AgentChat from '../features/chat/AgentChat'
import StoreOperations from '../features/analytics/StoreOperations'
import ProductInventory from '../features/products/ProductManager'
import CsvImports from '../features/imports/CsvImports'
import ProductTitles from '../features/titles/ProductTitles'
import OperationsConsole from '../features/v2/OperationsConsole'

interface Props {
  store: Store
  onSessionExpired: () => void
}

type PanelId = 'v2' | 'chat' | 'operations' | 'inventory' | 'imports' | 'titles'

const PANELS: { id: PanelId; label: string }[] = [
  { id: 'v2', label: '智能运营' },
  { id: 'chat', label: '运营问答' },
  { id: 'operations', label: '经营数据' },
  { id: 'inventory', label: '商品管理' },
  { id: 'imports', label: 'CSV 导入' },
  { id: 'titles', label: '标题审核' },
]

export default function StoreWorkspace({ store, onSessionExpired }: Props) {
  const [activePanel, setActivePanel] = useState<PanelId>('v2')
  // 仅记录当前店铺已打开的模块，避免初次进入工作区就触发所有模块的加载请求。
  const [visited, setVisited] = useState<PanelId[]>(['v2'])

  function handleNavigate(panelId: PanelId) {
    setActivePanel(panelId)
    // 函数式更新读取最新列表，连续切换时不会丢掉刚刚打开的模块。
    // 已打开的模块不再重复添加；数组展开创建新数组，不直接修改React的旧状态。
    setVisited((current) => current.includes(panelId) ? current : [...current, panelId])
  }

  return (
    <div className="store-workspace">
      <nav className="workspace-navigation" aria-label="工作区导航">
        {PANELS.map((panel) => <button key={panel.id} type="button"
          aria-pressed={activePanel === panel.id}
          aria-controls={`workspace-${panel.id}`}
          onClick={() => handleNavigate(panel.id)}>
          {panel.label}
        </button>)}
      </nav>
      <p className="hint workspace-tip">切换模块可继续当前店铺的工作。导入或修改数据后，请在相应页面重新查询或刷新商品。</p>

      {/* 外层容器始终存在，供导航指向；首次打开才挂载模块，之后用hidden隐藏。
          hidden保留组件及内部状态，条件渲染并卸载则会丢失聊天、草稿和文件选择。
          仅切模块时，在途请求继续处理；换店铺或退出会卸载整个工作区并取消等待。 */}
      <div id="workspace-v2" className="workspace-page" hidden={activePanel !== 'v2'}>
        {visited.includes('v2') && <OperationsConsole store={store} onSessionExpired={onSessionExpired} />}
      </div>
      <div id="workspace-chat" className="workspace-page" hidden={activePanel !== 'chat'}>
        {visited.includes('chat') && <AgentChat store={store} onSessionExpired={onSessionExpired} />}
      </div>
      <div id="workspace-operations" className="workspace-page" hidden={activePanel !== 'operations'}>
        {visited.includes('operations') && <StoreOperations store={store} onSessionExpired={onSessionExpired} />}
      </div>
      <div id="workspace-inventory" className="workspace-page" hidden={activePanel !== 'inventory'}>
        {visited.includes('inventory') && <ProductInventory store={store} onSessionExpired={onSessionExpired} />}
      </div>
      <div id="workspace-imports" className="workspace-page" hidden={activePanel !== 'imports'}>
        {visited.includes('imports') && <CsvImports store={store} onSessionExpired={onSessionExpired} />}
      </div>
      <div id="workspace-titles" className="workspace-page" hidden={activePanel !== 'titles'}>
        {visited.includes('titles') && <ProductTitles store={store} onSessionExpired={onSessionExpired} />}
      </div>
    </div>
  )
}

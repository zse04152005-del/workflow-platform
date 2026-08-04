import {
  Alert,
  Avatar,
  Badge,
  Button,
  Card,
  ConfigProvider,
  Descriptions,
  Flex,
  Form,
  Input,
  Layout,
  Menu,
  Modal,
  Progress,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Timeline,
  Typography,
  theme,
} from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { webConsoleConfig } from './config';

const { Header, Sider, Content } = Layout;
const { Title, Paragraph, Text } = Typography;

type PageKey = 'overview' | 'workflows' | 'instances' | 'approvals' | 'operations' | 'integrations' | 'audit';

const workflowRows = [
  { key: 'order-timeout', name: '订单超时关闭', status: '已发布', version: 7, updatedAt: '今天 18:42' },
  { key: 'refund-approval', name: '大额退款审批', status: '已发布', version: 4, updatedAt: '今天 17:08' },
  { key: 'alert-handler', name: '告警自动处理', status: '草稿', version: 2, updatedAt: '昨天 23:16' },
];

const instanceRows = [
  { key: 'ord-20260731-018', businessKey: 'order/O-88421', workflow: '订单超时关闭', status: '运行中', duration: '2m 13s' },
  { key: 'ref-20260731-009', businessKey: 'refund/R-2048', workflow: '大额退款审批', status: '等待审批', duration: '18m 41s' },
  { key: 'alt-20260731-204', businessKey: 'alert/A-701', workflow: '告警自动处理', status: '重试等待', duration: '4m 09s' },
];

const navigation: Array<{ key: PageKey; label: string }> = [
  { key: 'overview', label: '运行总览' },
  { key: 'workflows', label: '流程管理' },
  { key: 'instances', label: '实例与时间线' },
  { key: 'approvals', label: '人工待办' },
  { key: 'operations', label: '失败处理' },
  { key: 'integrations', label: '连接器与密钥' },
  { key: 'audit', label: '审计查询' },
];

function statusTag(status: string) {
  const color = status.includes('已发布') || status.includes('成功') ? 'green' : status.includes('等待') ? 'gold' : status.includes('重试') ? 'orange' : 'blue';
  return <Tag color={color}>{status}</Tag>;
}

function OverviewPage() {
  return (
    <Space orientation="vertical" size={20} className="page-stack">
      <section className="welcome-card" aria-labelledby="page-title">
        <div>
          <Text className="eyebrow">PRODUCTION · ASIA/SHANGHAI</Text>
          <Title id="page-title" level={1}>企业级事件驱动智能工作流平台</Title>
          <Paragraph>从发布、触发到租约恢复和人工审批，在一个可审计的工作台中完成。</Paragraph>
        </div>
        <Badge status="success" text="所有核心依赖正常" />
      </section>
      <div className="stat-grid" aria-label="运行指标">
        <Card><Statistic title="今日触发" value={12842} suffix="次" /></Card>
        <Card><Statistic title="运行中实例" value={376} /></Card>
        <Card><Statistic title="待审批" value={24} /></Card>
        <Card><Statistic title="死信" value={3} styles={{ content: { color: '#c2410c' } }} /></Card>
      </div>
      <div className="dashboard-grid">
        <Card title="执行健康度" extra={<Tag color="green">99.96%</Tag>}>
          <Space orientation="vertical" size={18} className="full-width">
            <div><Flex justify="space-between"><Text>任务成功率</Text><Text>99.2%</Text></Flex><Progress percent={99.2} showInfo={false} /></div>
            <div><Flex justify="space-between"><Text>Outbox 发布</Text><Text>98.7%</Text></Flex><Progress percent={98.7} showInfo={false} strokeColor="#0f766e" /></div>
            <div><Flex justify="space-between"><Text>Worker 容量</Text><Text>64%</Text></Flex><Progress percent={64} showInfo={false} strokeColor="#7c3aed" /></div>
          </Space>
        </Card>
        <Card title="最新运行事件">
          <Timeline items={[
            { color: 'green', content: '订单 O-88421 进入 HTTP 节点 · 刚刚' },
            { color: 'blue', content: '退款 R-2048 创建财务审批 · 2 分钟前' },
            { color: 'orange', content: '告警 A-701 将在 28 秒后重试 · 4 分钟前' },
            { color: 'gray', content: 'Worker worker-sh-07 心跳恢复 · 7 分钟前' },
          ]} />
        </Card>
      </div>
    </Space>
  );
}

function WorkflowsPage() {
  const [dirty, setDirty] = useState(false);
  const [published, setPublished] = useState(false);
  return (
    <Space orientation="vertical" size={20} className="page-stack">
      <Flex justify="space-between" align="center" gap={16} wrap="wrap">
        <div><Title level={2}>流程管理</Title><Text type="secondary">创建、克隆、编辑、比较并发布不可变版本。</Text></div>
        <Space><Button>克隆流程</Button><Button type="primary">新建流程</Button></Space>
      </Flex>
      {dirty && <Alert type="warning" showIcon message="存在未保存修改" description="离开页面前请保存草稿或放弃修改。" />}
      {published && <Alert type="success" showIcon message="版本 v8 已发布" />}
      <Table
        pagination={false}
        dataSource={workflowRows}
        columns={[
          { title: '流程', dataIndex: 'name' },
          { title: '状态', dataIndex: 'status', render: statusTag },
          { title: '最新版本', dataIndex: 'version', render: (value: number) => `v${value}` },
          { title: '更新时间', dataIndex: 'updatedAt' },
          { title: '操作', render: () => <Button type="link">查看版本</Button> },
        ]}
      />
      <div className="editor-grid">
        <Card title="定义编辑器" extra={<Tag color="blue">JSON / YAML</Tag>}>
          <Form layout="vertical">
            <Form.Item label="流程名称"><Input aria-label="流程名称" defaultValue="order-timeout" onChange={() => setDirty(true)} /></Form.Item>
            <Form.Item label="工作流定义">
              <Input.TextArea className="code-editor" rows={13} defaultValue={'schemaVersion: 1\nname: order-timeout\nnodes:\n  - id: start\n    type: start\n  - id: wait\n    type: delay\n  - id: end\n    type: end'} onChange={() => setDirty(true)} />
            </Form.Item>
            <Flex justify="space-between"><Button onClick={() => setDirty(false)}>保存草稿</Button><Button type="primary" onClick={() => setPublished(true)}>校验并发布</Button></Flex>
          </Form>
        </Card>
        <Space orientation="vertical" size={16} className="full-width">
          <Card title="只读 DAG 预览">
            <div className="dag-preview" aria-label="Start 到 Delay 到 End 的流程图"><span>Start</span><b>→</b><span>Delay</span><b>→</b><span>End</span></div>
          </Card>
          <Card title="版本差异 v7 → 草稿"><Descriptions column={1} size="small" items={[
            { key: '1', label: '/nodes/1/config/duration', children: 'PT5M → PT8M' },
            { key: '2', label: '/metadata/owner', children: 'ops → commerce' },
          ]} /></Card>
        </Space>
      </div>
    </Space>
  );
}

function InstancesPage() {
  return (
    <Space orientation="vertical" size={20} className="page-stack">
      <Title level={2}>实例与时间线</Title>
      <Flex gap={12} wrap="wrap"><Input.Search aria-label="业务键搜索" placeholder="搜索 business key" className="search-control" /><Select aria-label="状态筛选" defaultValue="all" options={[{ value: 'all', label: '全部状态' }, { value: 'running', label: '运行中' }, { value: 'failed', label: '失败' }]} /></Flex>
      <Table pagination={false} dataSource={instanceRows} columns={[
        { title: '业务键', dataIndex: 'businessKey' },
        { title: '流程', dataIndex: 'workflow' },
        { title: '状态', dataIndex: 'status', render: statusTag },
        { title: '耗时', dataIndex: 'duration' },
        { title: 'Trace', render: () => <Button type="link">打开 Trace</Button> },
      ]} />
      <Card title="order/O-88421 时间线" extra={<Tag>attempt 2</Tag>}>
        <Timeline items={[
          { color: 'green', content: 'Start · 成功 · 输入摘要 9f3a…' },
          { color: 'green', content: 'Condition · true 分支 · 12 ms' },
          { color: 'blue', content: 'HTTP · worker-sh-03 · lease generation 4' },
          { color: 'gray', content: 'Delay · 计划于 18:50:00 继续' },
        ]} />
      </Card>
    </Space>
  );
}

function ApprovalsPage() {
  const [decision, setDecision] = useState<string>();
  return (
    <Space orientation="vertical" size={20} className="page-stack">
      <Title level={2}>人工待办</Title>
      {decision && <Alert type="success" showIcon message={`退款 R-2048 已${decision}`} />}
      <Card title="大额退款审批 · R-2048" extra={<Tag color="gold">18 分钟后到期</Tag>}>
        <Descriptions bordered column={{ xs: 1, md: 2 }} items={[
          { key: '1', label: '退款金额', children: '¥ 50,000.00' },
          { key: '2', label: '申请人', children: 'commerce-service' },
          { key: '3', label: '候选角色', children: 'finance-approver' },
          { key: '4', label: '敏感字段', children: '<redacted>' },
        ]} />
        <Flex justify="flex-end" gap={12} className="card-actions"><Button aria-label="拒绝" danger onClick={() => setDecision('拒绝')}>拒绝</Button><Button aria-label="转交" onClick={() => Modal.info({ title: '转交审批', content: '转交必须填写处理人和原因。' })}>转交</Button><Button aria-label="批准" type="primary" onClick={() => setDecision('批准')}>批准</Button></Flex>
      </Card>
    </Space>
  );
}

function OperationsPage() {
  return (
    <Space orientation="vertical" size={20} className="page-stack">
      <Title level={2}>失败处理</Title>
      <Alert type="info" showIcon message="控制操作必须填写原因；取消为协作式，不撤销已发生的外部副作用。" />
      <Table pagination={false} dataSource={[{ key: 'dlq-1', instance: 'alert/A-701', code: 'HTTP_TIMEOUT', attempts: 5, summary: 'downstream timeout · credential <redacted>' }]} columns={[
        { title: '实例', dataIndex: 'instance' }, { title: '错误码', dataIndex: 'code' }, { title: 'attempt', dataIndex: 'attempts' }, { title: '脱敏摘要', dataIndex: 'summary' },
        { title: '操作', render: () => <Space><Button size="small">从失败节点重放</Button><Button size="small">新实例重放</Button></Space> },
      ]} />
      <Card title="实例控制"><Form layout="inline"><Form.Item label="原因" required><Input placeholder="请输入操作原因" /></Form.Item><Form.Item><Space><Button>暂停</Button><Button>恢复</Button><Button danger>取消</Button></Space></Form.Item></Form></Card>
    </Space>
  );
}

function IntegrationsPage() {
  return (
    <Space orientation="vertical" size={20} className="page-stack">
      <Title level={2}>连接器与密钥引用</Title>
      <Alert type="success" showIcon message="页面只展示 opaque Secret 引用，永不返回明文。" />
      <Table pagination={false} dataSource={[{ key: 'erp', name: 'ERP Production', endpoint: 'https://api.erp.example', credentialRef: 'opaque-ref://commerce/erp-credential', status: '正常' }]} columns={[
        { title: '连接器', dataIndex: 'name' }, { title: '受管 Endpoint', dataIndex: 'endpoint' }, { title: 'Secret 引用', dataIndex: 'credentialRef', render: (value: string) => <code>{value}</code> }, { title: '状态', dataIndex: 'status', render: statusTag },
      ]} />
    </Space>
  );
}

function AuditPage() {
  return (
    <Space orientation="vertical" size={20} className="page-stack">
      <Title level={2}>审计查询</Title>
      <Table pagination={false} dataSource={[{ key: 'a1', time: '19:02:14', actor: 'li.ming', action: 'human_task.approve', resource: 'refund/R-2048', reason: 'verified evidence', trace: '7f8a…3c21' }]} columns={[
        { title: '时间', dataIndex: 'time' }, { title: '操作者', dataIndex: 'actor' }, { title: '动作', dataIndex: 'action' }, { title: '资源', dataIndex: 'resource' }, { title: '原因', dataIndex: 'reason' }, { title: 'Trace', dataIndex: 'trace' },
      ]} />
    </Space>
  );
}

export function App() {
  const [page, setPage] = useState<PageKey>('overview');
  const content = useMemo(() => {
    const pages: Record<PageKey, ReactNode> = {
      overview: <OverviewPage />, workflows: <WorkflowsPage />, instances: <InstancesPage />, approvals: <ApprovalsPage />, operations: <OperationsPage />, integrations: <IntegrationsPage />, audit: <AuditPage />,
    };
    return pages[page];
  }, [page]);
  return (
    <ConfigProvider locale={zhCN} theme={{ algorithm: theme.defaultAlgorithm, token: { colorPrimary: '#145b5a', borderRadius: 8, colorBgLayout: '#f3f5f2', fontFamily: 'Inter, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif' } }}>
      <Layout className="console-shell">
        <Sider breakpoint="lg" collapsedWidth="0" className="console-sider">
          <div className="console-brand"><span>WF</span><div><strong>Workflow</strong><small>CONTROL PLANE</small></div></div>
          <Menu theme="dark" mode="inline" selectedKeys={[page]} items={navigation} onClick={({ key }) => setPage(key as PageKey)} />
          <div className="sider-status"><Badge status="success" text="Control Plane 正常" /><Text>{webConsoleConfig.apiBaseUrl}</Text></div>
        </Sider>
        <Layout>
          <Header className="console-header">
            <Space wrap><Select aria-label="租户切换" defaultValue="acme" options={[{ value: 'acme', label: 'Acme 集团' }, { value: 'demo', label: '演示租户' }]} /><Select aria-label="项目切换" defaultValue="commerce" options={[{ value: 'commerce', label: '电商平台' }, { value: 'ops', label: '运维自动化' }]} /></Space>
            <Space><Tag color="green">生产</Tag><Avatar>LM</Avatar><Text>李明 · 项目管理员</Text></Space>
          </Header>
          <Content className="console-content"><main>{content}</main></Content>
        </Layout>
      </Layout>
    </ConfigProvider>
  );
}

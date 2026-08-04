import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('App', () => {
  it('renders the authenticated operational overview with tenant and project selectors', () => {
    render(<App />);
    expect(screen.getByRole('heading', { level: 1, name: '企业级事件驱动智能工作流平台' })).toBeInTheDocument();
    expect(screen.getByLabelText('租户切换')).toBeInTheDocument();
    expect(screen.getByLabelText('项目切换')).toBeInTheDocument();
    expect(screen.getByText('所有核心依赖正常')).toBeInTheDocument();
  });

  it('navigates to workflows and warns about unsaved edits', () => {
    render(<App />);
    fireEvent.click(screen.getByText('流程管理'));
    expect(screen.getByRole('heading', { level: 2, name: '流程管理' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('流程名称'), { target: { value: 'changed-name' } });
    expect(screen.getByText('存在未保存修改')).toBeInTheDocument();
    expect(screen.getByLabelText('Start 到 Delay 到 End 的流程图')).toBeInTheDocument();
  });

  it('supports the first approval interaction', () => {
    render(<App />);
    fireEvent.click(screen.getByText('人工待办'));
    fireEvent.click(screen.getByRole('button', { name: '批准' }));
    expect(screen.getByText('退款 R-2048 已批准')).toBeInTheDocument();
  });
});

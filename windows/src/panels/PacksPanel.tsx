import { useMemo, useState, type JSX } from 'react';
import { useStore } from '../state';
import { Badge, Button, Card, EmptyState, Field, Modal, Select, TextArea, TextInput } from '../components/ui';
import { packBanner, pickPackBanner, useAssetImage, localizedEn, invalidateAssetImage } from '../lib';
import type { Pack, PackView } from '../../shared/types';

const SECTIONS = ['arcaea', 'mainstory', 'mainstory2', 'sidestory', 'collab', 'archive', 'variety'];

export function PacksPanel(): JSX.Element {
  const { snapshot, runSnapshot, notify } = useStore();
  const [creating, setCreating] = useState(false);
  const [newId, setNewId] = useState('');
  const [newName, setNewName] = useState('');
  const [newSection, setNewSection] = useState('sidestory');
  const [removing, setRemoving] = useState<PackView | null>(null);
  const [migrateTo, setMigrateTo] = useState('');

  const packs = snapshot?.packs ?? [];

  const sectionOptions = useMemo(
    () => SECTIONS.map((section) => ({ value: section, label: section })),
    [],
  );

  if (!snapshot) {
    return <EmptyState title="请先在「工程」页导入 APK" />;
  }

  return (
    <div className="flex flex-col gap-3">
      <Card
        title={`曲包（${packs.length}）`}
        actions={
          <>
            <Button size="sm" variant="primary" onClick={() => setCreating(true)}>
              新建曲包
            </Button>
          </>
        }
      >
        <p className="mb-3 text-[12px] leading-relaxed text-slate-400">
          曲包对应 <span className="font-mono text-slate-300">assets/songs/packlist</span>。
          横幅文件名由曲包 id 决定：<span className="font-mono text-slate-300">select_&lt;id&gt;.png</span> 与
          <span className="mx-1 font-mono text-slate-300">1080_select_&lt;id&gt;.png</span>。
          歌曲通过自身的 <span className="font-mono text-slate-300">set</span> 字段归属到曲包。
        </p>

        <div className="flex flex-col gap-2">
          {packs.map((view) => (
            <PackRow
              key={view.pack.id}
              view={view}
              sectionOptions={sectionOptions}
              onRemove={() => {
                setRemoving(view);
                setMigrateTo(packs.find((p) => p.pack.id !== view.pack.id)?.pack.id ?? '');
              }}
            />
          ))}
          {packs.length === 0 && (
            <div className="py-6 text-center text-[12px] text-slate-500">
              当前 APK 的 packlist 中没有任何曲包。
            </div>
          )}
        </div>
      </Card>

      <Modal
        open={creating}
        title="新建曲包"
        onClose={() => setCreating(false)}
        width="max-w-lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreating(false)}>
              取消
            </Button>
            <Button
              variant="primary"
              disabled={!newId.trim()}
              onClick={async () => {
                await runSnapshot('正在创建曲包…', () =>
                  window.api.packs.create({ id: newId.trim(), section: newSection, name: newName.trim() }),
                );
                notify('info', `已创建曲包 ${newId.trim()}，别忘了在「歌曲」页为它导入横幅图片并添加歌曲。`);
                setCreating(false);
                setNewId('');
                setNewName('');
              }}
            >
              创建
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <Field label="曲包 id" hint="只能包含字母、数字与下划线，会作为横幅文件名的一部分">
            <TextInput value={newId} onChange={setNewId} placeholder="mypack" />
          </Field>
          <Field label="显示名称（en）">
            <TextInput value={newName} onChange={setNewName} placeholder="My Pack" />
          </Field>
          <Field label="分组 section">
            <Select value={newSection} onChange={setNewSection} options={sectionOptions} />
          </Field>
        </div>
      </Modal>

      <Modal
        open={Boolean(removing)}
        title={`删除曲包 ${removing?.pack.id ?? ''}`}
        onClose={() => setRemoving(null)}
        width="max-w-lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoving(null)}>
              取消
            </Button>
            <Button
              variant="danger"
              onClick={async () => {
                if (!removing) return;
                await runSnapshot('正在删除曲包…', () =>
                  window.api.packs.remove(removing.pack.id, migrateTo || undefined),
                );
                notify(
                  'success',
                  migrateTo
                    ? `已删除曲包，其下歌曲已迁移到 ${migrateTo}`
                    : '已删除曲包及其下所有歌曲（含资源文件）',
                );
                setRemoving(null);
              }}
            >
              确认删除
            </Button>
          </>
        }
      >
        <p className="mb-3 text-[12px] leading-relaxed text-slate-300">
          该曲包下有 <strong>{removing?.songCount ?? 0}</strong> 首歌曲。
        </p>
        <Field label="迁移到其它曲包（留空表示连同歌曲一起删除）">
          <Select
            value={migrateTo}
            onChange={setMigrateTo}
            options={[
              { value: '', label: '不迁移，删除这些歌曲' },
              ...packs
                .filter((p) => p.pack.id !== removing?.pack.id)
                .map((p) => ({ value: p.pack.id, label: p.pack.id })),
            ]}
          />
        </Field>
      </Modal>
    </div>
  );
}

function PackRow({
  view,
  sectionOptions,
  onRemove,
}: {
  view: PackView;
  sectionOptions: Array<{ value: string; label: string }>;
  onRemove: () => void;
}): JSX.Element {
  const { runSnapshot, run, notify } = useStore();
  const { pack } = view;
  const [id, setId] = useState(pack.id);
  const [name, setName] = useState(localizedEn(pack.name_localized));
  const [description, setDescription] = useState(localizedEn(pack.description_localized));
  const [section, setSection] = useState(pack.section ?? 'sidestory');

  const bannerRel = pickPackBanner(view.banners, pack.id);
  const banner = useAssetImage(bannerRel);
  const bannerHint = view.banners.select
    ? 'select_<id>.png'
    : view.banners.hd
      ? '1080_select_<id>.png（高清回退）'
      : null;

  const dirty =
    id !== pack.id ||
    name !== localizedEn(pack.name_localized) ||
    description !== localizedEn(pack.description_localized) ||
    section !== (pack.section ?? 'sidestory');

  const importBanner = async (hd: boolean): Promise<void> => {
    const src = await window.api.dialog.openFile('选择曲包横幅图片', [
      { name: '图片', extensions: ['png', 'jpg', 'jpeg'] },
    ]);
    if (!src) return;
    await runSnapshot('正在写入横幅…', () =>
      window.api.assets.importFile(packBanner(id, hd), src),
    );
    invalidateAssetImage(packBanner(id, hd));
    await run('刷新预览', async () => {
      // 触发重新读取图片缓存
      await window.api.assets.readBase64(packBanner(id, hd));
    });
    notify('success', '横幅已写入，导出后生效');
  };

  const save = async (): Promise<void> => {
    const patch: Partial<Pack> = {
      id: id.trim(),
      section,
      name_localized: { ...(pack.name_localized ?? {}), en: name },
      description_localized: { ...(pack.description_localized ?? {}), en: description },
    };
    await runSnapshot('正在保存曲包…', () => window.api.packs.update(pack.id, patch));
    notify('success', `已更新曲包 ${patch.id}`);
  };

  return (
    <div className="flex gap-3 rounded-md border border-slate-800 bg-slate-950/40 p-3">
      <div className="flex w-40 shrink-0 flex-col gap-1">
        <div className="flex h-[72px] w-40 items-center justify-center overflow-hidden rounded border border-slate-800 bg-slate-900">
          {banner ? (
            <img src={banner} alt={pack.id} className="h-full w-full object-cover" />
          ) : (
            <span className="px-2 text-center text-[11px] text-rose-300/80">缺少横幅</span>
          )}
        </div>
        <div className="flex gap-1">
          <Button size="sm" variant="ghost" onClick={() => void importBanner(false)}>
            导入横幅
          </Button>
          <Button size="sm" variant="ghost" onClick={() => void importBanner(true)}>
            导入1080
          </Button>
        </div>
        <div className="flex gap-1">
          <Badge tone={view.banners.select ? 'emerald' : 'rose'}>
            select {view.banners.select ? '✓' : '✗'}
          </Badge>
          <Badge tone={view.banners.hd ? 'emerald' : 'slate'}>
            1080 {view.banners.hd ? '✓' : '—'}
          </Badge>
        </div>
        <div className="truncate text-[10.5px] text-slate-500">
          {bannerHint ? `显示：${bannerHint}` : '无可用横幅'}
        </div>
      </div>

      <div className="grid flex-1 grid-cols-2 gap-2 xl:grid-cols-4">
        <Field label="曲包 id">
          <TextInput value={id} onChange={setId} />
        </Field>
        <Field label="分组 section">
          <Select value={section} onChange={setSection} options={sectionOptions} />
        </Field>
        <Field label="名称（en）">
          <TextInput value={name} onChange={setName} />
        </Field>
        <Field label="歌曲数">
          <div className="flex h-[30px] items-center gap-2 px-2 text-[12px] text-slate-400">
            {view.songCount} 首
            {view.songCount === 0 && view.pack.is_extend_pack !== true && (
              <Badge tone="rose">空曲包 · 会闪退</Badge>
            )}
          </div>
        </Field>
        <Field label="描述（en）" className="col-span-2 xl:col-span-4">
          <TextArea value={description} onChange={setDescription} rows={2} />
        </Field>
        <div className="col-span-2 flex items-center gap-2 xl:col-span-4">
          <Button size="sm" variant="primary" disabled={!dirty} onClick={() => void save()}>
            保存修改
          </Button>
          {dirty && <Badge tone="amber">有未保存的修改</Badge>}
          <div className="flex-1" />
          <Button size="sm" variant="danger" onClick={onRemove}>
            删除曲包
          </Button>
        </div>
      </div>
    </div>
  );
}

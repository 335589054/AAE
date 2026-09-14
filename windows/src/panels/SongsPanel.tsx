import { useEffect, useMemo, useState, type JSX } from 'react';
import { useStore } from '../state';
import { Badge, Button, EmptyState, Modal, Field, Select, TextInput, Toggle, cx } from '../components/ui';
import { diffShort, localizedEn, pickSongThumb, useAssetImage } from '../lib';
import { SongEditor } from './SongEditor';
import type { SongView, SongZipInspection } from '../../shared/types';

export function SongsPanel(): JSX.Element {
  const { snapshot, runSnapshot, notify } = useStore();
  const [query, setQuery] = useState('');
  const [packFilter, setPackFilter] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<SongView | null>(null);
  const [creating, setCreating] = useState(false);
  /** 删除歌曲时是否连带删除被删空的曲包（非 extend 空曲包会导致游戏闪退） */
  const [alsoDeletePack, setAlsoDeletePack] = useState(true);

  // 新增表单
  const [newId, setNewId] = useState('');
  const [newTitle, setNewTitle] = useState('');
  const [newPack, setNewPack] = useState('');
  const [zipPath, setZipPath] = useState<string | null>(null);
  const [zipInfo, setZipInfo] = useState<SongZipInspection | null>(null);
  const [zipChecking, setZipChecking] = useState(false);

  const songs = snapshot?.songs ?? [];
  const packs = snapshot?.packs ?? [];

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return songs.filter((view) => {
      if (packFilter && view.song.set !== packFilter) return false;
      if (!q) return true;
      const title = localizedEn(view.song.title_localized).toLowerCase();
      return view.song.id.toLowerCase().includes(q) || title.includes(q);
    });
  }, [songs, query, packFilter]);

  useEffect(() => {
    if (filtered.length === 0) return;
    if (!selectedId || !songs.some((s) => s.song.id === selectedId)) {
      setSelectedId(filtered[0].song.id);
    }
  }, [filtered, selectedId, songs]);

  const selected = songs.find((s) => s.song.id === selectedId) ?? null;

  /**
   * 若这首歌是某个「普通曲包」的最后一首，删除后该包会变成空曲包。
   * 实测（对照原始 mod 与改包后的 APK）非 extend 的空曲包会让游戏打开歌单时闪退，
   * 因此这里提前提示，并默认连带删除该曲包。
   */
  const emptiedPack =
    pendingDelete && pendingDelete.song.set
      ? (packs.find((p) => p.pack.id === pendingDelete.song.set) ?? null)
      : null;
  const willEmptyPack = Boolean(
    emptiedPack && emptiedPack.songCount === 1 && emptiedPack.pack.is_extend_pack !== true,
  );

  const resetCreateForm = (): void => {
    setNewId('');
    setNewTitle('');
    setZipPath(null);
    setZipInfo(null);
  };

  const chooseZip = async (): Promise<void> => {
    const picked = await window.api.dialog.openFile('选择歌曲资源压缩包', [
      { name: 'ZIP 压缩包', extensions: ['zip'] },
      { name: '所有文件', extensions: ['*'] },
    ]);
    if (!picked) return;
    setZipChecking(true);
    try {
      const info = await window.api.assets.inspectZip(picked);
      setZipPath(picked);
      setZipInfo(info);
      if (info.metadata && !newTitle.trim()) {
        const metaTitle = localizedEn(info.metadata.title_localized);
        if (metaTitle) setNewTitle(metaTitle);
      }
    } catch (err) {
      setZipPath(null);
      setZipInfo(null);
      notify('error', err instanceof Error ? err.message : String(err));
    } finally {
      setZipChecking(false);
    }
  };

  const openContextMenu = async (view: SongView): Promise<void> => {
    setSelectedId(view.song.id);
    const action = await window.api.menu
      .show([
        { id: 'up', label: '上移', enabled: view.index > 0 },
        { id: 'down', label: '下移', enabled: view.index < songs.length - 1 },
        { id: 'delete', label: '删除曲目', separatorBefore: true },
      ])
      .catch(() => null);
    if (!action) return;
    if (action === 'up' || action === 'down') {
      const target = view.index + (action === 'up' ? -1 : 1);
      await runSnapshot('正在调整曲目顺序…', () => window.api.songs.reorder(view.index, target));
    } else if (action === 'delete') {
      setPendingDelete(view);
    }
  };

  if (!snapshot) return <EmptyState title="请先在「工程」页导入 APK" />;

  return (
    <div className="flex min-h-0 flex-1 gap-3">
      <div className="flex w-[320px] shrink-0 flex-col gap-2">
        <div className="flex gap-2">
          <TextInput value={query} onChange={setQuery} placeholder="搜索曲名 / id" />
          <Button variant="primary" onClick={() => setCreating(true)} title="新增歌曲">
            ＋
          </Button>
        </div>
        <Select
          value={packFilter}
          onChange={setPackFilter}
          options={[
            { value: '', label: `全部曲包（${songs.length}）` },
            ...packs.map((p) => ({
              value: p.pack.id,
              label: `${p.pack.id}（${p.songCount}）`,
            })),
          ]}
        />
        <div className="min-h-0 flex-1 overflow-auto rounded-lg border border-slate-800 bg-slate-900/30">
          {filtered.map((view) => (
            <SongListItem
              key={view.song.id}
              view={view}
              active={view.song.id === selectedId}
              onClick={() => setSelectedId(view.song.id)}
              onContextMenu={() => void openContextMenu(view)}
            />
          ))}
          {filtered.length === 0 && (
            <div className="p-4 text-center text-[12px] text-slate-500">没有匹配的歌曲</div>
          )}
        </div>
        <div className="text-[11px] leading-relaxed text-slate-600">
          提示：在曲目上点右键可以上移 / 下移 / 删除。
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-auto pr-1">
        {selected ? (
          <SongEditor
            view={selected}
            packs={packs.map((p) => p.pack)}
            onRenamed={(nextId) => setSelectedId(nextId)}
            onDeleted={() => setSelectedId(null)}
          />
        ) : (
          <EmptyState title="从左侧选择一首歌曲进行编辑" />
        )}
      </div>

      {/* 新增歌曲 */}
      <Modal
        open={creating}
        title="新增歌曲"
        onClose={() => setCreating(false)}
        width="max-w-2xl"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreating(false)}>
              取消
            </Button>
            <Button
              variant="primary"
              disabled={!newId.trim() || !(newPack || packs[0]?.pack.id) || zipChecking}
              onClick={async () => {
                const setId = newPack || packs[0]?.pack.id;
                if (!setId) {
                  notify('error', '请先创建至少一个曲包');
                  return;
                }
                const created = newId.trim();
                await runSnapshot('正在新增歌曲…', () =>
                  window.api.songs.create({
                    id: created,
                    setId,
                    title: newTitle.trim(),
                    zipPath: zipPath ?? undefined,
                  }),
                );
                setSelectedId(created);
                setCreating(false);
                resetCreateForm();
                notify(
                  'success',
                  zipInfo && zipPath
                    ? `已新增歌曲并导入 ${zipInfo.resources.length} 个资源文件`
                    : '已新增歌曲条目。请到「资源文件」页导入封面与谱面，否则导出后该曲目将不可用。',
                );
              }}
            >
              创建
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <Field label="歌曲 id" hint="必须与将要创建的目录名 assets/songs/<id>/ 一致；只能包含字母、数字、下划线与连字符">
            <TextInput value={newId} onChange={setNewId} placeholder="mysong01" />
          </Field>
          <Field label="曲名（en）">
            <TextInput value={newTitle} onChange={setNewTitle} placeholder="My New Song" />
          </Field>
          <Field label="所属曲包 set">
            <Select
              value={newPack || packs[0]?.pack.id || ''}
              onChange={setNewPack}
              options={packs.map((p) => ({ value: p.pack.id, label: p.pack.id }))}
              disabled={packs.length === 0}
            />
          </Field>
          {packs.length === 0 && (
            <p className="text-[12px] text-amber-300/80">当前没有曲包，请先到「曲包」页创建。</p>
          )}

          <div className="rounded-md border border-slate-800 bg-slate-950/40 p-3">
            <div className="mb-2 flex items-center gap-2">
              <span className="text-[12px] font-medium text-slate-300">资源压缩包（可选）</span>
              <Button size="sm" onClick={() => void chooseZip()} disabled={zipChecking}>
                {zipChecking ? '正在解析…' : '选择 zip'}
              </Button>
              {zipPath && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setZipPath(null);
                    setZipInfo(null);
                  }}
                >
                  移除
                </Button>
              )}
            </div>
            <p className="text-[11.5px] leading-relaxed text-slate-500">
              压缩包内可以直接是歌曲目录的内容（base.jpg / 3.aff / base.ogg…），也可以是
              <span className="mx-1 font-mono">歌曲文件夹/…</span> 或
              <span className="mx-1 font-mono">assets/songs/歌曲文件夹/…</span>
              ；若包含 <span className="font-mono">songlist</span> /
              <span className="mx-1 font-mono">slst</span> /
              <span className="mx-1 font-mono">song.json</span>，会自动读取其中的元数据。
            </p>

            {zipInfo && zipPath && (
              <div className="mt-2 flex flex-col gap-1.5">
                <div className="truncate font-mono text-[11px] text-slate-400">{zipPath}</div>
                <div className="flex flex-wrap gap-1.5">
                  <Badge tone="violet">资源 {zipInfo.resources.length} 个</Badge>
                  <Badge tone={zipInfo.hasJacket ? 'emerald' : 'rose'}>
                    封面 {zipInfo.hasJacket ? '✓' : '✗'}
                  </Badge>
                  <Badge tone={zipInfo.hasChart ? 'emerald' : 'rose'}>
                    谱面 {zipInfo.hasChart ? '✓' : '✗'}
                  </Badge>
                  <Badge tone={zipInfo.hasAudio ? 'emerald' : 'rose'}>
                    音频 {zipInfo.hasAudio ? '✓' : '✗'}
                  </Badge>
                  {zipInfo.root && <Badge tone="slate">已剥离 {zipInfo.root}/</Badge>}
                </div>
                {zipInfo.metadata && (
                  <div className="text-[11.5px] text-emerald-300/85">
                    已从 {zipInfo.metadataSource} 读取元数据：
                    {[
                      localizedEn(zipInfo.metadata.title_localized) || null,
                      zipInfo.metadata.artist || null,
                      zipInfo.metadata.bpm ? `BPM ${zipInfo.metadata.bpm}` : null,
                      zipInfo.metadata.difficulties
                        ? `${zipInfo.metadata.difficulties.length} 个难度`
                        : null,
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </div>
                )}
                {zipInfo.warnings.map((warning) => (
                  <div key={warning} className="text-[11.5px] text-amber-300/85">
                    {warning}
                  </div>
                ))}
                {zipInfo.resources.length > 0 && (
                  <details className="mt-1">
                    <summary className="cursor-pointer text-[11.5px] text-slate-400">
                      查看将导入的文件列表
                    </summary>
                    <div className="mt-1 flex flex-wrap gap-1">
                      {zipInfo.resources.map((name) => (
                        <span
                          key={name}
                          className="rounded border border-slate-700 bg-slate-900 px-1.5 py-0.5 font-mono text-[11px] text-slate-400"
                        >
                          {name}
                        </span>
                      ))}
                    </div>
                  </details>
                )}
              </div>
            )}
          </div>
        </div>
      </Modal>

      {/* 删除确认（来自右键菜单） */}
      <Modal
        open={Boolean(pendingDelete)}
        title="确认删除歌曲"
        onClose={() => setPendingDelete(null)}
        width="max-w-md"
        footer={
          <>
            <Button variant="ghost" onClick={() => setPendingDelete(null)}>
              取消
            </Button>
            <Button
              variant="danger"
              onClick={async () => {
                if (!pendingDelete) return;
                const target = pendingDelete;
                const packToRemove = willEmptyPack && alsoDeletePack ? emptiedPack?.pack.id : undefined;
                await runSnapshot('正在删除歌曲…', async () => {
                  const afterSong = await window.api.songs.remove(target.song.id);
                  if (!packToRemove) return afterSong;
                  return window.api.packs.remove(packToRemove);
                });
                setPendingDelete(null);
                notify(
                  'success',
                  packToRemove
                    ? `已删除歌曲 ${target.song.id}，并移除了被删空的曲包 ${packToRemove}`
                    : `已删除歌曲 ${target.song.id}`,
                );
              }}
            >
              确认删除
            </Button>
          </>
        }
      >
        <p className="text-[12.5px] leading-relaxed text-slate-300">
          将删除 <span className="font-mono">{pendingDelete?.song.id}</span> 及其目录下的{' '}
          <strong>{pendingDelete?.files.length ?? 0}</strong> 个文件。该操作在导出后不可撤销。
        </p>

        {willEmptyPack && emptiedPack && (
          <div className="mt-3 rounded-md border border-rose-900/70 bg-rose-950/30 p-3">
            <p className="text-[12.5px] font-semibold text-rose-200">
              ⚠ 这是曲包「{emptiedPack.pack.id}」的最后一首歌
            </p>
            <p className="mt-1 text-[12px] leading-relaxed text-rose-200/85">
              该曲包没有 <span className="font-mono">is_extend_pack</span> 标记（属于普通曲包）。
              删除后它会变成空曲包，<strong>实测会导致游戏打开歌单时直接闪退</strong>。
              （原始 mod 里的 empty 曲包都带 <span className="font-mono">is_extend_pack: true</span>，所以是安全的。）
            </p>
            <div className="mt-2">
              <Toggle
                checked={alsoDeletePack}
                onChange={setAlsoDeletePack}
                label={`同时删除曲包「${emptiedPack.pack.id}」及其横幅（推荐）`}
              />
            </div>
            {!alsoDeletePack && (
              <p className="mt-1.5 text-[12px] text-amber-200/85">
                你选择保留空曲包：导出前请务必为该曲包添加至少一首歌，或给它加上{' '}
                <span className="font-mono">"is_extend_pack": true</span>，否则游戏会闪退。
              </p>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}

function SongListItem({
  view,
  active,
  onClick,
  onContextMenu,
}: {
  view: SongView;
  active: boolean;
  onClick: () => void;
  onContextMenu: () => void;
}): JSX.Element {
  const thumb = useAssetImage(pickSongThumb(view));

  return (
    <button
      type="button"
      onClick={onClick}
      onContextMenu={(event) => {
        event.preventDefault();
        onContextMenu();
      }}
      className={cx(
        'flex w-full items-center gap-2.5 border-b border-slate-800/70 px-2.5 py-2 text-left transition-colors',
        active ? 'bg-violet-950/50' : 'hover:bg-slate-800/40',
      )}
    >
      <div className="h-10 w-10 shrink-0 overflow-hidden rounded border border-slate-800 bg-slate-900">
        {thumb ? (
          <img src={thumb} alt="" className="h-full w-full object-cover" />
        ) : (
          <div className="flex h-full w-full items-center justify-center text-[9px] text-slate-600">
            无图
          </div>
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-[12.5px] font-medium text-slate-200">
          {localizedEn(view.song.title_localized) || view.song.id}
        </div>
        <div className="flex items-center gap-1.5">
          <span className="truncate font-mono text-[10.5px] text-slate-500">{view.song.id}</span>
          {view.song.set && <Badge tone="slate">{view.song.set}</Badge>}
        </div>
      </div>
      <div className="flex shrink-0 flex-col items-end gap-0.5">
        {view.charts.map((chart) => (
          <span
            key={chart.ratingClass}
            className={cx(
              'font-mono text-[10px]',
              chart.hasChart ? 'text-slate-400' : 'text-rose-400',
            )}
            title={chart.hasChart ? '谱面存在' : '缺少谱面文件'}
          >
            {diffShort(chart.ratingClass)}
          </span>
        ))}
        {!view.dirExists && <span className="text-[10px] text-rose-400">无目录</span>}
      </div>
    </button>
  );
}

import { useEffect, useMemo, useState, type JSX } from 'react';
import { useStore } from '../state';
import {
  Badge,
  Button,
  Card,
  Field,
  Modal,
  Select,
  TextArea,
  TextInput,
  Toggle,
} from '../components/ui';
import {
  DIFF_FULL,
  DIFF_SHORT,
  diffFull,
  diffShort,
  invalidateAssetImage,
  invalidateSongImages,
  localizedEn,
  pickSongJacket,
  songAsset,
  useAssetImage,
} from '../lib';
import type { Pack, Song, SongDifficulty, SongView } from '../../shared/types';

const TABS = [
  { id: 'basic', label: '基本信息' },
  { id: 'diff', label: '难度与定数' },
  { id: 'assets', label: '资源文件' },
  { id: 'danger', label: '删除' },
] as const;

type TabId = (typeof TABS)[number]['id'];

const JACKETS: Array<{ file: string; label: string; note: string }> = [
  { file: 'base.jpg', label: 'base.jpg', note: '标准封面' },
  { file: 'base_256.jpg', label: 'base_256.jpg', note: '列表缩略图（256px）' },
  { file: '1080_base.jpg', label: '1080_base.jpg', note: '高清封面（可选）' },
  { file: '1080_base_256.jpg', label: '1080_base_256.jpg', note: '高清缩略图（可选）' },
];

export function SongEditor({
  view,
  packs,
  onRenamed,
  onDeleted,
}: {
  view: SongView;
  packs: Pack[];
  onRenamed: (id: string) => void;
  onDeleted: () => void;
}): JSX.Element {
  const { runSnapshot, notify, snapshot } = useStore();
  const [tab, setTab] = useState<TabId>('basic');
  const [draft, setDraft] = useState<Song>(() => structuredClone(view.song));
  const [confirmDelete, setConfirmDelete] = useState(false);
  const totalSongs = snapshot?.songs.length ?? 0;

  useEffect(() => {
    setDraft(structuredClone(view.song));
  }, [view.song.id]);

  const dirty = useMemo(
    () => JSON.stringify(draft) !== JSON.stringify(view.song),
    [draft, view.song],
  );

  const patch = (changes: Partial<Song>): void => setDraft((prev) => ({ ...prev, ...changes }));

  const save = async (): Promise<void> => {
    const originalId = view.song.id;
    const nextId = String(draft.id ?? '').trim();
    await runSnapshot('正在保存歌曲…', () => window.api.songs.update(originalId, draft));
    invalidateSongImages(originalId);
    if (nextId && nextId !== originalId) {
      onRenamed(nextId);
      notify('success', `已保存，歌曲 id 由 ${originalId} 改为 ${nextId}，目录与谱面一并迁移`);
    } else {
      notify('success', `已保存歌曲 ${originalId}`);
    }
  };

  const importAsset = async (file: string, kind: 'image' | 'audio' | 'chart'): Promise<void> => {
    const filters =
      kind === 'image'
        ? [{ name: '图片', extensions: ['png', 'jpg', 'jpeg'] }]
        : kind === 'audio'
          ? [{ name: '音频', extensions: ['ogg'] }]
          : [{ name: '谱面', extensions: ['aff'] }];
    const src = await window.api.dialog.openFile(`选择 ${file}`, filters);
    if (!src) return;
    await runSnapshot(`正在写入 ${file}…`, () =>
      window.api.assets.importFile(songAsset(view.song.id, file), src),
    );
    invalidateAssetImage(songAsset(view.song.id, file));
    notify('success', `${file} 已写入，导出后生效`);
  };

  return (
    <div className="flex flex-col gap-3 pb-16">
      <Card
        title={
          <div className="flex items-center gap-2">
            <span>{localizedEn(view.song.title_localized) || view.song.id}</span>
            <span className="font-mono text-[11px] font-normal text-slate-500">{view.song.id}</span>
            {!view.dirExists && <Badge tone="rose">缺少目录</Badge>}
          </div>
        }
        actions={
          <>
            {TABS.map((item) => (
              <Button
                key={item.id}
                size="sm"
                variant={tab === item.id ? 'primary' : 'ghost'}
                onClick={() => setTab(item.id)}
              >
                {item.label}
              </Button>
            ))}
          </>
        }
      >
        {tab === 'basic' && <BasicTab draft={draft} patch={patch} packs={packs} />}
        {tab === 'diff' && <DiffTab draft={draft} patch={patch} view={view} onImport={importAsset} />}
        {tab === 'assets' && <AssetsTab view={view} onImport={importAsset} />}
        {tab === 'danger' && (
          <div className="flex flex-col gap-3">
            <p className="text-[12px] leading-relaxed text-amber-200/90">
              删除歌曲会从 <span className="font-mono">songlist</span> 移除该条目，并删除
              <span className="mx-1 font-mono">assets/songs/{view.song.id}/</span>
              下的全部资源文件（封面 / 音频 / 谱面）。该操作在导出后不可撤销。
            </p>
            <div>
              <Button variant="danger" onClick={() => setConfirmDelete(true)}>
                删除这首歌曲
              </Button>
            </div>
          </div>
        )}
      </Card>

      <div className="fixed bottom-0 left-[200px] right-0 z-30 flex items-center gap-3 border-t border-slate-800 bg-slate-950/95 px-4 py-2.5 backdrop-blur">
        <Button
          size="sm"
          variant="ghost"
          disabled={view.index <= 0}
          title="在 songlist 中上移，影响游戏内曲目排序"
          onClick={() =>
            void runSnapshot('正在调整曲目顺序…', () =>
              window.api.songs.reorder(view.index, view.index - 1),
            )
          }
        >
          ↑ 上移
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={view.index >= totalSongs - 1}
          title="在 songlist 中下移，影响游戏内曲目排序"
          onClick={() =>
            void runSnapshot('正在调整曲目顺序…', () =>
              window.api.songs.reorder(view.index, view.index + 1),
            )
          }
        >
          ↓ 下移
        </Button>
        <span className="text-[12px] text-slate-500">
          第 {view.index + 1} / {totalSongs} 位
        </span>
        <div className="flex-1" />
        <span className="text-[12px] text-slate-400">
          {dirty ? '有未保存的修改' : '已与当前工程状态一致'}
        </span>
        {dirty && (
          <Button variant="ghost" onClick={() => setDraft(structuredClone(view.song))}>
            放弃修改
          </Button>
        )}
        <Button variant="primary" disabled={!dirty} onClick={() => void save()}>
          保存到工程
        </Button>
      </div>

      <Modal
        open={confirmDelete}
        title="确认删除歌曲"
        onClose={() => setConfirmDelete(false)}
        width="max-w-md"
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmDelete(false)}>
              取消
            </Button>
            <Button
              variant="danger"
              onClick={async () => {
                await runSnapshot('正在删除歌曲…', () => window.api.songs.remove(view.song.id));
                invalidateSongImages(view.song.id);
                setConfirmDelete(false);
                onDeleted();
                notify('success', `已删除歌曲 ${view.song.id}`);
              }}
            >
              确认删除
            </Button>
          </>
        }
      >
        <p className="text-[12.5px] leading-relaxed text-slate-300">
          将删除 <span className="font-mono">{view.song.id}</span> 及其目录下的{' '}
          <strong>{view.files.length}</strong> 个文件。请确认已经导出过需要保留的 APK。
        </p>
      </Modal>
    </div>
  );
}

/* ------------------------------- 基本信息 ------------------------------- */

function BasicTab({
  draft,
  patch,
  packs,
}: {
  draft: Song;
  patch: (changes: Partial<Song>) => void;
  packs: Pack[];
}): JSX.Element {
  const title = localizedEn(draft.title_localized);
  return (
    <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
      <Field label="歌曲 id" hint="与目录名一致；修改后会自动迁移目录">
        <TextInput value={draft.id ?? ''} onChange={(value) => patch({ id: value })} />
      </Field>
      <Field label="曲名（en）">
        <TextInput
          value={title}
          onChange={(value) => patch({ title_localized: { ...(draft.title_localized ?? {}), en: value } })}
        />
      </Field>
      <Field label="曲师 artist">
        <TextInput value={draft.artist ?? ''} onChange={(value) => patch({ artist: value })} />
      </Field>
      <Field label="所属曲包 set">
        <Select
          value={draft.set ?? ''}
          onChange={(value) => patch({ set: value })}
          options={[
            { value: draft.set ?? '', label: draft.set ?? '(未设置)' },
            ...packs
              .filter((p) => p.id !== draft.set)
              .map((p) => ({ value: p.id, label: p.id })),
          ]}
        />
      </Field>
      <Field label="BPM（显示值）" hint="允许区间写法，例如 210-222">
        <TextInput value={draft.bpm ?? ''} onChange={(value) => patch({ bpm: value })} />
      </Field>
      <Field label="BPM base（数值）">
        <TextInput
          type="number"
          value={draft.bpm_base ?? 0}
          onChange={(value) => patch({ bpm_base: Number(value) })}
        />
      </Field>
      <Field label="side" hint="阵营 / 配色索引，建议沿用同曲包其它歌曲的取值">
        <TextInput
          type="number"
          value={draft.side ?? 0}
          onChange={(value) => patch({ side: Number(value) })}
        />
      </Field>
      <Field label="背景 bg" hint="对应 assets/img/bg/1080/<bg>.jpg">
        <TextInput value={draft.bg ?? ''} onChange={(value) => patch({ bg: value })} />
      </Field>
      <Field label="版本 version">
        <TextInput value={draft.version ?? ''} onChange={(value) => patch({ version: value })} />
      </Field>
      <Field label="日期 date" hint="Unix 时间戳（秒）">
        <TextInput
          type="number"
          value={draft.date ?? 0}
          onChange={(value) => patch({ date: Number(value) })}
        />
      </Field>
      <Field label="试听起点 audioPreview（毫秒）">
        <TextInput
          type="number"
          value={draft.audioPreview ?? 0}
          onChange={(value) => patch({ audioPreview: Number(value) })}
        />
      </Field>
      <Field label="试听终点 audioPreviewEnd（毫秒）">
        <TextInput
          type="number"
          value={draft.audioPreviewEnd ?? 0}
          onChange={(value) => patch({ audioPreviewEnd: Number(value) })}
        />
      </Field>
      <Field label="purchase">
        <TextInput value={draft.purchase ?? ''} onChange={(value) => patch({ purchase: value })} />
      </Field>
    </div>
  );
}

/* ------------------------------ 难度与定数 ------------------------------ */

function DiffTab({
  draft,
  patch,
  view,
  onImport,
}: {
  draft: Song;
  patch: (changes: Partial<Song>) => void;
  view: SongView;
  onImport: (file: string, kind: 'chart' | 'audio') => Promise<void>;
}): JSX.Element {
  const difficulties = draft.difficulties ?? [];

  const updateDifficulty = (index: number, changes: Partial<SongDifficulty>): void => {
    const next = difficulties.map((item, i) => (i === index ? { ...item, ...changes } : item));
    patch({ difficulties: next });
  };

  const removeDifficulty = (index: number): void => {
    patch({ difficulties: difficulties.filter((_, i) => i !== index) });
  };

  const addDifficulty = (): void => {
    const used = new Set(difficulties.map((d) => d.ratingClass));
    const free = [0, 1, 2, 3, 4].find((value) => !used.has(value));
    if (free === undefined) return;
    patch({
      difficulties: [
        ...difficulties,
        { ratingClass: free, rating: 1, ratingPlus: false, chartDesigner: '', jacketDesigner: '' },
      ].sort((a, b) => a.ratingClass - b.ratingClass),
    });
  };

  const allUsed = difficulties.length >= 5;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <Button size="sm" onClick={addDifficulty} disabled={allUsed}>
          添加难度
        </Button>
        <span className="text-[11.5px] text-slate-500">
          ratingClass 的数值同时决定谱面与独立音频的文件名（如 3 → 3.aff / 3.ogg）。
          {allUsed && ' 0–4 已全部使用。'}
        </span>
      </div>

      <div className="flex flex-col gap-2">
        {difficulties.map((difficulty, index) => {
          const status = view.charts.find((c) => c.ratingClass === difficulty.ratingClass);
          return (
            <div
              key={`${difficulty.ratingClass}-${index}`}
              className="rounded-md border border-slate-800 bg-slate-950/40 p-3"
            >
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <Badge tone="violet">
                  {diffShort(difficulty.ratingClass)} · {diffFull(difficulty.ratingClass)}
                </Badge>
                <Badge tone={status?.hasChart ? 'emerald' : 'rose'}>
                  谱面 {difficulty.ratingClass}.aff {status?.hasChart ? '✓' : '缺失'}
                </Badge>
                <Badge tone={status?.hasAudio ? 'emerald' : difficulty.audioOverride ? 'rose' : 'slate'}>
                  音频 {difficulty.ratingClass}.ogg {status?.hasAudio ? '✓' : '—'}
                </Badge>
                <div className="flex-1" />
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => void onImport(`${difficulty.ratingClass}.aff`, 'chart')}
                >
                  导入谱面
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => void onImport(`${difficulty.ratingClass}.ogg`, 'audio')}
                >
                  导入独立音频
                </Button>
                <Button size="sm" variant="ghost" onClick={() => removeDifficulty(index)}>
                  移除
                </Button>
              </div>

              <div className="grid grid-cols-2 gap-2 xl:grid-cols-5">
                <Field label="ratingClass">
                  <Select
                    value={String(difficulty.ratingClass)}
                    onChange={(value) => updateDifficulty(index, { ratingClass: Number(value) })}
                    options={[0, 1, 2, 3, 4].map((value) => ({
                      value: String(value),
                      label: `${value} · ${DIFF_FULL[value]} (${DIFF_SHORT[value]})`,
                    }))}
                  />
                </Field>
                <Field label="定数 rating">
                  <TextInput
                    type="number"
                    value={difficulty.rating ?? 0}
                    onChange={(value) => updateDifficulty(index, { rating: Number(value) })}
                  />
                </Field>
                <Field label="谱师 chartDesigner">
                  <TextInput
                    value={difficulty.chartDesigner ?? ''}
                    onChange={(value) => updateDifficulty(index, { chartDesigner: value })}
                  />
                </Field>
                <Field label="曲绘师 jacketDesigner">
                  <TextInput
                    value={difficulty.jacketDesigner ?? ''}
                    onChange={(value) => updateDifficulty(index, { jacketDesigner: value })}
                  />
                </Field>
                <Field label="该难度标题（en，可选）">
                  <TextInput
                    value={localizedEn(difficulty.title_localized)}
                    onChange={(value) =>
                      updateDifficulty(index, { title_localized: { en: value } })
                    }
                  />
                </Field>
                <div className="col-span-2 flex items-end gap-2 xl:col-span-3">
                  <Toggle
                    checked={Boolean(difficulty.ratingPlus)}
                    onChange={(value) => updateDifficulty(index, { ratingPlus: value })}
                    label="显示 + 号（如 9+）"
                  />
                  <Toggle
                    checked={Boolean(difficulty.audioOverride)}
                    onChange={(value) => updateDifficulty(index, { audioOverride: value })}
                    label="使用独立音频 audioOverride"
                  />
                </div>
              </div>
            </div>
          );
        })}
        {difficulties.length === 0 && (
          <div className="py-6 text-center text-[12px] text-slate-500">
            该歌曲没有任何难度定义，导出后不会出现在游戏内。
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------- 资源文件 ------------------------------- */

function AssetsTab({
  view,
  onImport,
}: {
  view: SongView;
  onImport: (file: string, kind: 'image' | 'audio' | 'chart') => Promise<void>;
}): JSX.Element {
  const { runSnapshot, notify } = useStore();
  const [affFile, setAffFile] = useState<string | null>(null);

  const autoJacketRel = pickSongJacket(view);
  const autoJacket = useAssetImage(autoJacketRel);
  const autoJacketFile = autoJacketRel ? autoJacketRel.split('/').pop() : null;

  const removeAsset = async (file: string): Promise<void> => {
    await runSnapshot(`正在标记删除 ${file}…`, () =>
      window.api.assets.remove(songAsset(view.song.id, file)),
    );
    invalidateAssetImage(songAsset(view.song.id, file));
    notify('info', `${file} 将在导出时从 APK 中移除`);
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-3 rounded-md border border-slate-800 bg-slate-950/40 p-3">
        <div className="flex h-28 w-28 shrink-0 items-center justify-center overflow-hidden rounded border border-slate-800 bg-slate-900">
          {autoJacket ? (
            <img src={autoJacket} alt="封面" className="h-full w-full object-contain" />
          ) : (
            <span className="px-2 text-center text-[11px] text-slate-600">没有任何封面文件</span>
          )}
        </div>
        <div className="flex flex-col gap-1">
          <div className="text-[12px] font-medium text-slate-300">封面预览（自动选择存在的文件）</div>
          <div className="font-mono text-[11.5px] text-slate-400">
            {autoJacketFile ?? '未找到 base.jpg / 1080_base.jpg / base_256.jpg / 1080_base_256.jpg'}
          </div>
          <div className="text-[11px] leading-relaxed text-slate-500">
            选择顺序：1080_base.jpg → base.jpg → 1080_base_256.jpg → base_256.jpg；
            列表中的缩略图则优先使用小尺寸文件。
          </div>
        </div>
      </div>

      <div>
        <div className="mb-2 text-[12px] font-medium text-slate-300">封面</div>
        <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
          {JACKETS.map((jacket) => (
            <JacketSlot
              key={jacket.file}
              songId={view.song.id}
              file={jacket.file}
              label={jacket.label}
              note={jacket.note}
              onImport={() => void onImport(jacket.file, 'image')}
              onRemove={() => void removeAsset(jacket.file)}
            />
          ))}
        </div>
      </div>

      <div>
        <div className="mb-2 text-[12px] font-medium text-slate-300">整曲音频</div>
        <div className="flex items-center gap-3 rounded-md border border-slate-800 bg-slate-950/40 p-3">
          <Badge tone={view.hasBaseAudio ? 'emerald' : 'amber'}>
            base.ogg {view.hasBaseAudio ? '存在' : '缺失'}
          </Badge>
          <span className="text-[11.5px] text-slate-500">
            若歌曲使用「剪曲」（每个难度各有 <span className="font-mono">&lt;n&gt;.ogg</span>
            ），则不需要 base.ogg。
          </span>
          <div className="flex-1" />
          <Button size="sm" onClick={() => void onImport('base.ogg', 'audio')}>
            导入 / 替换
          </Button>
          {view.hasBaseAudio && (
            <Button size="sm" variant="ghost" onClick={() => void removeAsset('base.ogg')}>
              移除
            </Button>
          )}
        </div>
      </div>

      <div>
        <div className="mb-2 text-[12px] font-medium text-slate-300">谱面文件（.aff）</div>
        <div className="flex flex-col gap-2">
          {view.charts.map((chart) => (
            <div
              key={chart.ratingClass}
              className="flex items-center gap-2 rounded-md border border-slate-800 bg-slate-950/40 px-3 py-2"
            >
              <Badge tone="sky">{diffShort(chart.ratingClass)}</Badge>
              <span className="font-mono text-[12px] text-slate-300">
                {chart.ratingClass}.aff
              </span>
              <Badge tone={chart.hasChart ? 'emerald' : 'rose'}>
                {chart.hasChart ? '存在' : '缺失'}
              </Badge>
              <div className="flex-1" />
              <Button
                size="sm"
                variant="ghost"
                disabled={!chart.hasChart}
                onClick={() => setAffFile(`${chart.ratingClass}.aff`)}
              >
                查看 / 编辑文本
              </Button>
              <Button
                size="sm"
                onClick={() => void onImport(`${chart.ratingClass}.aff`, 'chart')}
              >
                导入
              </Button>
              {chart.hasChart && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => void removeAsset(`${chart.ratingClass}.aff`)}
                >
                  移除
                </Button>
              )}
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="mb-2 text-[12px] font-medium text-slate-300">
          目录内全部文件（{view.files.length}）
        </div>
        <div className="flex flex-wrap gap-1.5 rounded-md border border-slate-800 bg-slate-950/40 p-2">
          {view.files.map((file) => (
            <span
              key={file}
              className="rounded border border-slate-700 bg-slate-900 px-1.5 py-0.5 font-mono text-[11px] text-slate-400"
            >
              {file}
            </span>
          ))}
          {view.files.length === 0 && (
            <span className="text-[11.5px] text-rose-300/80">目录不存在或为空</span>
          )}
        </div>
      </div>

      {affFile && (
        <AffEditor
          relPath={songAsset(view.song.id, affFile)}
          title={`${view.song.id} / ${affFile}`}
          onClose={() => setAffFile(null)}
        />
      )}
    </div>
  );
}

function JacketSlot({
  songId,
  file,
  label,
  note,
  onImport,
  onRemove,
}: {
  songId: string;
  file: string;
  label: string;
  note: string;
  onImport: () => void;
  onRemove: () => void;
}): JSX.Element {
  const url = useAssetImage(songAsset(songId, file));
  return (
    <div className="flex flex-col gap-1.5 rounded-md border border-slate-800 bg-slate-950/40 p-2">
      <div className="flex h-32 items-center justify-center overflow-hidden rounded border border-slate-800 bg-slate-900">
        {url ? (
          <img src={url} alt={label} className="h-full w-full object-contain" />
        ) : (
          <span className="text-[11px] text-slate-600">未提供</span>
        )}
      </div>
      <div className="font-mono text-[11px] text-slate-300">{label}</div>
      <div className="text-[10.5px] text-slate-500">{note}</div>
      <div className="flex gap-1">
        <Button size="sm" variant="ghost" onClick={onImport}>
          导入
        </Button>
        {url && (
          <Button size="sm" variant="ghost" onClick={onRemove}>
            移除
          </Button>
        )}
      </div>
    </div>
  );
}

/* ------------------------------ AFF 文本编辑 ------------------------------ */

function AffEditor({
  relPath,
  title,
  onClose,
}: {
  relPath: string;
  title: string;
  onClose: () => void;
}): JSX.Element {
  const { run, notify } = useStore();
  const [text, setText] = useState('');
  const [original, setOriginal] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    void run('正在读取谱面…', async () => {
      const content = (await window.api.assets.readText(relPath)) ?? '';
      setText(content);
      setOriginal(content);
      setLoading(false);
    });
  }, [relPath, run]);

  const dirty = text !== original;

  return (
    <Modal
      open
      title={`谱面文本 · ${title}`}
      onClose={onClose}
      width="max-w-4xl"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
          <Button
            variant="primary"
            disabled={!dirty}
            onClick={async () => {
              await run('正在写入谱面…', async () => {
                await window.api.assets.writeText(relPath, text);
                setOriginal(text);
                notify('success', '谱面文本已写入，导出后生效');
              });
            }}
          >
            保存文本
          </Button>
        </>
      }
    >
      {loading ? (
        <div className="py-8 text-center text-[12px] text-slate-500">正在读取…</div>
      ) : (
        <>
          <p className="mb-2 text-[11.5px] leading-relaxed text-slate-500">
            这是 Arcaea 的明文谱面格式（<span className="font-mono">AudioOffset</span> /
            <span className="mx-1 font-mono">timing</span> /
            <span className="mx-1 font-mono">arc</span> 等）。工具不做语法校验，请自行确保格式正确。
          </p>
          <TextArea value={text} onChange={setText} rows={22} mono />
        </>
      )}
    </Modal>
  );
}

// 永続化・revision・Undo/Redo。保存は一時ファイル→renameで原子的に行う。
import fs from 'node:fs';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { applyOps, validateProject, migrateProject, OpError } from './model.mjs';
import { sampleProject } from './sample.mjs';

const HISTORY_LIMIT = 100;

export class Store extends EventEmitter {
  constructor(dataDir) {
    super();
    this.dir = dataDir;
    this.file = path.join(dataDir, 'project.json');
    this.historyFile = path.join(dataDir, 'history.json');
    this.assetDir = path.join(dataDir, 'assets');
    fs.mkdirSync(this.assetDir, { recursive: true });
    if (fs.existsSync(this.file)) {
      const doc = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      this.revision = doc.revision;
      this.project = migrateProject(doc.project);
      const errors = validateProject(this.project);
      if (errors.length) throw new Error(`data/project.json が不正です:\n${errors.join('\n')}`);
    } else {
      this.revision = 0;
      this.project = sampleProject();
      this.#save();
    }
    this.history = fs.existsSync(this.historyFile) ? JSON.parse(fs.readFileSync(this.historyFile, 'utf8')) : { undo: [], redo: [] };
    for (const h of [...this.history.undo, ...this.history.redo]) migrateProject(h.project);
  }

  snapshot() { return { revision: this.revision, project: this.project, canUndo: this.history.undo.length > 0, canRedo: this.history.redo.length > 0 }; }

  // revision が一致しないときは 409 相当のエラー
  apply(revision, ops, meta = {}) {
    if (revision != null && revision !== this.revision) {
      const e = new OpError(`revision が古いです（現在 ${this.revision}、指定 ${revision}）。最新を取得してから再実行してください`);
      e.status = 409;
      throw e;
    }
    const next = applyOps(this.project, ops);
    this.history.undo.push({ project: this.project, label: meta.label || ops.map((o) => o.op).join(','), source: meta.source || 'api' });
    if (this.history.undo.length > HISTORY_LIMIT) this.history.undo.shift();
    this.history.redo = [];
    this.project = next;
    this.revision++;
    this.#save();
    this.emit('change', { revision: this.revision, source: meta.source || 'api', label: meta.label });
    return this.snapshot();
  }

  undo(source) { return this.#travel('undo', 'redo', source); }
  redo(source) { return this.#travel('redo', 'undo', source); }

  #travel(from, to, source) {
    const entry = this.history[from].pop();
    if (!entry) throw new OpError(from === 'undo' ? '元に戻す操作がありません' : 'やり直す操作がありません');
    // 素材が削除済みなら戻せない
    for (const a of Object.values(entry.project.assets)) {
      if (!fs.existsSync(path.join(this.assetDir, a.file))) { this.history[from].push(entry); throw new OpError(`素材ファイル ${a.file} がないため戻せません`); }
    }
    this.history[to].push({ project: this.project, label: entry.label, source: entry.source });
    this.project = entry.project;
    this.revision++;
    this.#save();
    this.emit('change', { revision: this.revision, source: source || 'api', action: from, label: entry.label });
    return this.snapshot();
  }

  historyList() {
    return {
      undo: this.history.undo.map((h) => ({ label: h.label, source: h.source })).reverse(),
      redo: this.history.redo.map((h) => ({ label: h.label, source: h.source })).reverse(),
    };
  }

  assetPath(file) {
    const p = path.join(this.assetDir, path.basename(file));
    return p;
  }

  #save() {
    writeAtomic(this.file, JSON.stringify({ revision: this.revision, project: this.project }, null, 2));
    if (this.history) writeAtomic(this.historyFile, JSON.stringify(this.history));
  }
}

export function writeAtomic(file, data) {
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, data);
  fs.renameSync(tmp, file);
}

import fs from 'fs';
import path from 'path';
import {fileURLToPath} from 'url';
import fetch from 'node-fetch';
import sharp from 'sharp';
import {execSync} from 'child_process';
import fg from 'fast-glob';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const IMAGE_URL = process.env.IMAGE_URL || '';
const IMAGE_BASE64 = process.env.IMAGE_BASE64 || '';
const REPO_IMAGE_PATH = process.env.REPO_IMAGE_PATH || '';
const COMMIT_CHANGES = (process.env.COMMIT_CHANGES || 'false').toLowerCase() === 'true';
const GITHUB_TOKEN = process.env.GITHUB_TOKEN || '';

async function getImageBuffer() {
  if (IMAGE_BASE64 && IMAGE_BASE64.trim() !== '') {
    console.log('Using image from base64 input.');
    const base64 = IMAGE_BASE64.replace(/^data:.*;base64,/, '').trim();
    return Buffer.from(base64, 'base64');
  }

  if (REPO_IMAGE_PATH && REPO_IMAGE_PATH.trim() !== '') {
    const repoPath = path.resolve(process.cwd(), REPO_IMAGE_PATH);
    if (!fs.existsSync(repoPath)) {
      throw new Error(`repo image path not found: ${repoPath}`);
    }
    console.log(`Using image from repository path: ${REPO_IMAGE_PATH}`);
    return fs.readFileSync(repoPath);
  }

  if (IMAGE_URL && IMAGE_URL.trim() !== '') {
    console.log(`Downloading image from URL: ${IMAGE_URL}`);
    const res = await fetch(IMAGE_URL);
    if (!res.ok) throw new Error(`Failed to download image: ${res.status} ${res.statusText}`);
    const arrayBuffer = await res.arrayBuffer();
    return Buffer.from(arrayBuffer);
  }

  throw new Error('No input image provided. Set IMAGE_URL, IMAGE_BASE64, or REPO_IMAGE_PATH.');
}

function ensureDir(filePath) {
  const dir = path.dirname(filePath);
  fs.mkdirSync(dir, { recursive: true });
}

async function generatePngSizes(buffer, sizes, outDirPrefix = 'generated-icons/tmp') {
  const outPaths = [];
  for (const size of sizes) {
    const outRel = `${outDirPrefix}/icon-${size}x${size}.png`;
    const out = path.resolve(process.cwd(), outRel);
    ensureDir(out);
    await sharp(buffer)
      .resize(size, size, { fit: 'cover' })
      .png({ quality: 90 })
      .toFile(out);
    console.log(`Wrote ${path.relative(process.cwd(), out)} (${size}x${size})`);
    outPaths.push(out);
  }
  return outPaths;
}

async function createIcoFromPngs(pngPaths, icoOutPath) {
  const pngToIco = (await import('png-to-ico')).default;
  const icoBuffer = await pngToIco(pngPaths);
  ensureDir(icoOutPath);
  fs.writeFileSync(icoOutPath, icoBuffer);
  console.log(`Wrote ICO: ${path.relative(process.cwd(), icoOutPath)}`);
  return icoOutPath;
}

async function findAndReplaceIcos(icoBuffer) {
  // 搜索常见 ICO 文件（排除 node_modules/.git/build/dist 等）
  const patterns = ['**/app.ico', '**/favicon.ico', '**/*.ico'];
  const ignore = ['**/node_modules/**', '**/.git/**', '**/build/**', '**/dist/**', '**/out/**'];
  const matches = await fg(patterns, { ignore, dot: true, onlyFiles: true, unique: true });
  const replaced = [];
  for (const rel of matches) {
    const abs = path.resolve(process.cwd(), rel);
    try {
      ensureDir(abs);
      fs.writeFileSync(abs, icoBuffer);
      console.log(`Replaced ICO: ${rel}`);
      replaced.push(rel);
    } catch (err) {
      console.warn(`Failed to replace ${rel}: ${err.message}`);
    }
  }
  return replaced;
}

function androidFolderToSize(folderName) {
  // 基于 mipmap 名称映射尺寸（常见映射）
  if (/mipmap-?mdpi/.test(folderName)) return 48;
  if (/mipmap-?hdpi/.test(folderName)) return 72;
  if (/mipmap-?xhdpi/.test(folderName)) return 96;
  if (/mipmap-?xxhdpi/.test(folderName)) return 144;
  if (/mipmap-?xxxhdpi/.test(folderName)) return 192;
  // fallback: try drawable densities
  if (/drawable-?mdpi/.test(folderName)) return 48;
  if (/drawable-?hdpi/.test(folderName)) return 72;
  if (/drawable-?xhdpi/.test(folderName)) return 96;
  if (/drawable-?xxhdpi/.test(folderName)) return 144;
  return null;
}

async function findAndReplaceAndroidLaunchers(buffer) {
  // 找到 mipmap/drawable 下 ic_launcher*.png 文件所在的文件夹并写入对应尺寸
  const ignore = ['**/node_modules/**', '**/.git/**', '**/build/**', '**/dist/**', '**/out/**'];
  const pngMatches = await fg(['**/res/mipmap-*/ic_launcher*.png', '**/res/drawable*/ic_launcher*.png', '**/mipmap-*/ic_launcher*.png'], { ignore, onlyFiles: true, unique: true });
  const replaced = [];
  // group by folder
  const folders = {};
  for (const p of pngMatches) {
    const dir = path.dirname(p);
    folders[dir] = true;
  }
  for (const dir of Object.keys(folders)) {
    const folderName = path.basename(dir);
    const size = androidFolderToSize(folderName);
    if (!size) continue;
    const outRel = path.join(dir, 'ic_launcher.png');
    const outAbs = path.resolve(process.cwd(), outRel);
    ensureDir(outAbs);
    await sharp(buffer).resize(size, size, { fit: 'cover' }).png({ quality: 90 }).toFile(outAbs);
    console.log(`Replaced Android launcher: ${outRel} (${size}x${size})`);
    replaced.push(outRel);
  }
  return replaced;
}

async function maybeCommit(files) {
  if (!COMMIT_CHANGES) {
    console.log('COMMIT_CHANGES is false → not committing files.');
    return;
  }
  if (!GITHUB_TOKEN) {
    throw new Error('COMMIT_CHANGES requested but GITHUB_TOKEN not provided.');
  }

  execSync('git config user.name "github-actions[bot]"');
  execSync('git config user.email "41898282+github-actions[bot]@users.noreply.github.com"');

  execSync(`git add ${files.map(f => `"${f}"`).join(' ')}`);
  try {
    execSync(`git commit -m "chore: update app icons (generated by workflow)"`);
  } catch (err) {
    console.log('No changes to commit or commit failed:', err.message);
    return;
  }

  const branch = process.env.GITHUB_REF?.replace('refs/heads/', '') || 'main';
  const remoteUrl = `https://x-access-token:${GITHUB_TOKEN}@github.com/${process.env.GITHUB_REPOSITORY}.git`;
  execSync(`git remote set-url origin "${remoteUrl}"`);
  execSync(`git push origin HEAD:${branch}`);
  console.log(`Committed and pushed changes to ${branch}`);
}

(async () => {
  try {
    const buf = await getImageBuffer();

    // ICO 常用尺寸
    const icoSizes = [16, 24, 32, 48, 64, 128, 256];

    // 生成单独 PNG（临时），用于合成 ICO 以及可用于替换其它 PNG 目标
    const tempPngs = await generatePngSizes(buf, icoSizes, 'generated-icons/tmp');

    // 生成 multi-size .ico 到临时文件
    const icoTempPath = path.resolve(process.cwd(), 'generated-icons/app.ico');
    await createIcoFromPngs(tempPngs, icoTempPath);
    const icoBuffer = fs.readFileSync(icoTempPath);

    // 1) 在仓库中查找并替换所有现有的 ICO/favicon
    const replacedIcos = await findAndReplaceIcos(icoBuffer);

    // 2) 查找并替换 Android ic_launcher PNG（如果存在）
    const replacedAndroidPngs = await findAndReplaceAndroidLaunchers(buf);

    // 3) 可选：把生成的 favicon 也写到常见前端位置（如果存在 public）
    const commonFaviconPaths = ['frontend/public/favicon.ico', 'public/favicon.ico', 'app/public/favicon.ico'];
    const writtenFavs = [];
    for (const rel of commonFaviconPaths) {
      const abs = path.resolve(process.cwd(), rel);
      try {
        ensureDir(abs);
        fs.writeFileSync(abs, icoBuffer);
        console.log(`Wrote common favicon: ${rel}`);
        writtenFavs.push(rel);
      } catch (err) {
        // ignore
      }
    }

    const allTouched = [...replacedIcos, ...replacedAndroidPngs, ...writtenFavs].map(p => p.replace(/\\/g, '/'));
    if (allTouched.length === 0) {
      console.log('No target icon files found to replace. You can adjust search patterns or provide explicit target paths.');
    } else {
      console.log('Replaced files:', allTouched);
    }

    // 合并提交列表（仅相对路径）
    const filesToCommit = allTouched;

    await maybeCommit(filesToCommit);

    console.log('Icon replacement finished successfully.');
  } catch (err) {
    console.error('Error during icon generation/replacement:', err);
    process.exit(1);
  }
})();

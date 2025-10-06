import fs from 'fs';
import path from 'path';
import archiver from 'archiver';

async function run(): Promise<void> {
    const root: string = path.resolve('.');
    const dist: string = path.join(root, 'dist');
    if (!fs.existsSync(dist)) {
        console.error('dist folder not found. Run `npm run build` first.');
        process.exit(1);
    }

    const targetDir: string = path.resolve(root, '..', 'app', 'app', 'src', 'main', 'assets');
    if (!fs.existsSync(targetDir)) {
        fs.mkdirSync(targetDir, { recursive: true });
    }

    const outPath: string = path.join(targetDir, 'frontend.zip');
    const output: fs.WriteStream = fs.createWriteStream(outPath);
    const archive: archiver.Archiver = archiver('zip', { zlib: { level: 9 } });

    output.on('close', () => {
        console.log(`frontend.zip created (${archive.pointer()} total bytes) at ${outPath}`);
    });

    archive.on('warning', (err: any) => {
        if (err && err.code === 'ENOENT') {
            console.warn(err);
        } else {
            throw err;
        }
    });

    archive.on('error', (err: Error) => {
        throw err;
    });

    archive.pipe(output);
    archive.directory(dist, false);
    await archive.finalize();
}

export default run;

run().catch(err => {
    console.error(err);
    process.exit(1);
});

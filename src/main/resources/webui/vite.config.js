import { defineConfig } from "vite";
import { resolve } from "path";

// JCEF loads files from plugin resources; keep URLs *relative*
export default defineConfig({
    base: "",                 // critical for relative paths
    root: "src",              // Source files are in src/
    build: {
        outDir: "../dist",    // Output to dist/ (relative to root)
        emptyOutDir: true,
        target: "es2020",     // safe for current JCEF Chromium
        sourcemap: false,
        rollupOptions: {
            input: resolve(__dirname, "src/index.html"),
            output: {
                inlineDynamicImports: true, // makes "standalone" even more self-contained
                // Disable hash for predictable filenames
                entryFileNames: 'assets/[name].js',
                chunkFileNames: 'assets/[name].js',
                assetFileNames: 'assets/[name].[ext]'
            }
        }
    }
});
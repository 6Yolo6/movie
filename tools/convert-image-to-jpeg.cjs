const path = require("path");
const sharp = require(path.resolve(__dirname, "../frontend/node_modules/sharp"));

const [, , inputPath, outputPath] = process.argv;
if (!inputPath || !outputPath) {
  console.error("Usage: node convert-image-to-jpeg.cjs <input> <output>");
  process.exit(2);
}

sharp(inputPath)
  .rotate()
  .jpeg({ quality: 88, mozjpeg: true })
  .toFile(outputPath)
  .catch((error) => {
    console.error(error && error.message ? error.message : String(error));
    process.exit(1);
  });
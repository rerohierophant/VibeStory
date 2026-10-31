/**
 * 把用户上传的风格参考图压成小图再存进 localStorage。
 * 原图动辄几 MB，直接存会撑爆 5MB 配额；统一缩到宽 480px 的 JPEG 后约 30–60KB。
 */
export const STYLE_IMAGE_MAX_WIDTH = 480;
const QUALITY = 0.8;

export async function readStyleImage(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new Error('请选择图片文件');
  }
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, STYLE_IMAGE_MAX_WIDTH / bitmap.width);
  const width = Math.round(bitmap.width * scale);
  const height = Math.round(bitmap.height * scale);

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('当前浏览器不支持图片处理');
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();

  return canvas.toDataURL('image/jpeg', QUALITY);
}

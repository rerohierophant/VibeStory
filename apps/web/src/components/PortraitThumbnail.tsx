import { useRef } from 'react';
import { PlaceholderImage } from './PlaceholderImage';

export function PortraitThumbnail({ seed }: { seed: string }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  function extendEdges(image: HTMLImageElement) {
    const canvas = canvasRef.current;
    if (!canvas || !image.naturalWidth || !image.naturalHeight) return;

    const scale = Math.min(window.devicePixelRatio || 1, 2);
    const width = Math.round(canvas.clientWidth * scale);
    const height = Math.round(canvas.clientHeight * scale);
    if (!width || !height) return;
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) return;

    const fit = Math.min(width / image.naturalWidth, height / image.naturalHeight);
    const imageWidth = Math.max(1, Math.round(image.naturalWidth * fit));
    const imageHeight = Math.max(1, Math.round(image.naturalHeight * fit));
    const left = Math.floor((width - imageWidth) / 2);
    const top = Math.floor((height - imageHeight) / 2);
    context.drawImage(image, left, top, imageWidth, imageHeight);

    // Stretch the rendered edge pixels so the fill meets the image at the same color on every row.
    context.imageSmoothingEnabled = false;
    const right = left + imageWidth;
    if (left > 0) context.drawImage(canvas, left, top, 1, imageHeight, 0, top, left, imageHeight);
    if (right < width) context.drawImage(canvas, right - 1, top, 1, imageHeight, right, top, width - right, imageHeight);
    const bottom = top + imageHeight;
    if (top > 0) context.drawImage(canvas, 0, top, width, 1, 0, 0, width, top);
    if (bottom < height) context.drawImage(canvas, 0, bottom - 1, width, 1, 0, bottom, width, height - bottom);
  }

  if (!seed.startsWith('/api/') && !seed.startsWith('data:image/')) {
    return <PlaceholderImage seed={seed} ratio={null} rounded="rounded-none" className="h-full w-full" />;
  }

  return (
    <div className="relative h-full w-full bg-(--vibe-portrait-matte)">
      <img src={seed} alt="" loading="lazy" className="block h-full w-full object-contain"
        onLoad={event => extendEdges(event.currentTarget)} />
      <canvas ref={canvasRef} aria-hidden="true" className="pointer-events-none absolute inset-0 h-full w-full" />
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 bg-[linear-gradient(90deg,rgba(0,0,0,.58),transparent_40%,transparent_60%,rgba(0,0,0,.58))]" />
    </div>
  );
}

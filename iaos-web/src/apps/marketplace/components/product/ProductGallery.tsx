import { useState } from 'react';
import { ChevronLeft, ChevronRight, Maximize2, ZoomIn } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/dialog';

interface ProductGalleryProps {
  images: string[];
  productName: string;
  discount?: number;
  inCarts?: number;
}

export const ProductGallery = ({ images, productName, discount, inCarts }: ProductGalleryProps) => {
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isZoomed, setIsZoomed] = useState(false);

  const nextImage = () => {
    setCurrentIndex((prev) => (prev + 1) % images.length);
  };

  const prevImage = () => {
    setCurrentIndex((prev) => (prev - 1 + images.length) % images.length);
  };

  return (
    <div className="flex gap-4">
      {/* Vertical Thumbnails - eBay Style */}
      <div className="hidden md:flex flex-col gap-2 w-20">
        {images.map((img, index) => (
          <button
            key={index}
            onClick={() => setCurrentIndex(index)}
            className={`gallery-thumbnail ${currentIndex === index ? 'active' : ''}`}
          >
            <img
              src={img}
              alt={`${productName} ${index + 1}`}
              className="w-full h-full object-cover"
            />
          </button>
        ))}
      </div>

      {/* Main Image */}
      <div className="flex-1 relative">
        <div className="gallery-main aspect-square bg-muted">
          {/* Badges */}
          <div className="absolute top-4 left-4 z-10 flex flex-col gap-2">
            {inCarts && inCarts > 5 && (
              <span className="badge-popularity inline-flex items-center">
                Em {inCarts} carrinhos
              </span>
            )}
            {discount && discount > 0 && (
              <span className="bg-destructive text-destructive-foreground font-semibold text-sm px-3 py-1.5 rounded-md">
                -{discount}% OFF
              </span>
            )}
          </div>

          {/* Action Buttons */}
          <div className="absolute top-4 right-4 z-10 flex gap-2">
            <Dialog>
              <DialogTrigger asChild>
                <Button
                  variant="secondary"
                  size="icon"
                  className="bg-card/90 hover:bg-card shadow-md"
                >
                  <Maximize2 className="h-4 w-4" />
                </Button>
              </DialogTrigger>
              <DialogContent className="max-w-4xl p-0">
                <img
                  src={images[currentIndex]}
                  alt={productName}
                  className="w-full h-auto"
                />
              </DialogContent>
            </Dialog>
          </div>

          {/* Main Image */}
          <img
            src={images[currentIndex]}
            alt={productName}
            className="w-full h-full object-cover transition-transform duration-300"
          />

          {/* Navigation Arrows */}
          <button
            onClick={prevImage}
            className="absolute left-3 top-1/2 -translate-y-1/2 bg-card/90 hover:bg-card p-2 rounded-full shadow-lg transition-all duration-200 hover:scale-110"
          >
            <ChevronLeft className="h-5 w-5" />
          </button>
          <button
            onClick={nextImage}
            className="absolute right-3 top-1/2 -translate-y-1/2 bg-card/90 hover:bg-card p-2 rounded-full shadow-lg transition-all duration-200 hover:scale-110"
          >
            <ChevronRight className="h-5 w-5" />
          </button>

          {/* Image Counter */}
          <div className="absolute bottom-4 left-1/2 -translate-x-1/2 bg-card/90 backdrop-blur-sm rounded-full px-3 py-1 text-sm text-muted-foreground">
            {currentIndex + 1} / {images.length}
          </div>
        </div>

        {/* Horizontal Thumbnails for Mobile */}
        <div className="flex md:hidden gap-2 overflow-x-auto pb-2 mt-4">
          {images.map((img, index) => (
            <button
              key={index}
              onClick={() => setCurrentIndex(index)}
              className={`gallery-thumbnail flex-shrink-0 ${currentIndex === index ? 'active' : ''}`}
            >
              <img
                src={img}
                alt={`${productName} ${index + 1}`}
                className="w-full h-full object-cover"
              />
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};

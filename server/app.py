"""FastAPI application for Photo Convert Web Studio, Loupe Preview, PDF Builder & Watched Folder."""

from __future__ import annotations

import io
import zipfile
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from server.converter import build_sample_photos, convert_image_bytes, generate_loupe_preview
from server.folder_watcher import WatchedFolderManager
from server.models import LOSSLESS_FORMATS, RAW_EXTENSIONS, SUPPORTED_FORMATS, ConvertOptions
from server.pdf_exporter import build_pdf_from_images
from server.store import ConvertStore
from server.versioning import read_current_version

REPO_ROOT = Path(__file__).resolve().parent.parent


class ConvertPayloadMixin(BaseModel):
    output_format: str = "webp"
    quality: int = 85
    target_size_kb: int = 0
    resize_mode: str = "original"
    max_megapixels: float = 16.0
    max_edge_px: int = 2048
    strip_exif: bool = True
    repair_filename_dates: bool = True
    normalize_srgb: bool = True
    remove_background: bool = False
    aspect_ratio: str = "original"
    frame_mode: str = "blur"
    watermark_text: str = ""
    watermark_position: str = "bottom-right"
    watermark_opacity: int = 65
    rename_pattern: str = "{orig}"
    preset_slug: str = "custom"

    def to_options(self) -> ConvertOptions:
        return ConvertOptions(
            output_format=self.output_format,  # type: ignore[arg-type]
            quality=self.quality,
            target_size_kb=self.target_size_kb,
            resize_mode=self.resize_mode,  # type: ignore[arg-type]
            max_megapixels=self.max_megapixels,
            max_edge_px=self.max_edge_px,
            strip_exif=self.strip_exif,
            repair_filename_dates=self.repair_filename_dates,
            normalize_srgb=self.normalize_srgb,
            remove_background=self.remove_background,
            aspect_ratio=self.aspect_ratio,  # type: ignore[arg-type]
            frame_mode=self.frame_mode,  # type: ignore[arg-type]
            watermark_text=self.watermark_text,
            watermark_position=self.watermark_position,  # type: ignore[arg-type]
            watermark_opacity=self.watermark_opacity,
            rename_pattern=self.rename_pattern,
            preset_slug=self.preset_slug,
        ).normalized()


class PresetCreatePayload(ConvertPayloadMixin):
    name: str
    description: str = ""


class SampleConvertPayload(ConvertPayloadMixin):
    output_format: str = "webp"
    quality: int = 84
    resize_mode: str = "megapixels"


class WatcherConfigPayload(ConvertPayloadMixin):
    enabled: bool = True


class ZipDownloadPayload(BaseModel):
    ids: list[str]


class PdfHistoryPayload(BaseModel):
    ids: list[str]
    page_size: str = "a4"
    margin: str = "small"
    document_scan_filter: bool = False


def create_app(
    data_dir: Path | None = None,
    webui_dir: Path | None = None,
) -> FastAPI:
    runtime_dir = data_dir or (REPO_ROOT / "data")
    outputs_dir = runtime_dir / "outputs"
    watch_inbox_dir = runtime_dir / "watch_inbox"
    outputs_dir.mkdir(parents=True, exist_ok=True)
    watch_inbox_dir.mkdir(parents=True, exist_ok=True)

    store = ConvertStore(runtime_dir / "photo_convert.db")
    watcher = WatchedFolderManager(watch_inbox_dir, outputs_dir, store)
    static_dir = webui_dir or (REPO_ROOT / "webui")

    app = FastAPI(
        title="Photo Convert Studio",
        version=read_current_version(REPO_ROOT),
        docs_url="/api/docs",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/api/status")
    def get_status() -> dict:
        return {
            "app": "Photo Convert",
            "version": read_current_version(REPO_ROOT),
            "supported_formats": list(SUPPORTED_FORMATS),
            "lossless_formats": sorted(LOSSLESS_FORMATS),
            "raw_extensions": sorted(RAW_EXTENSIONS),
            "summary": store.summary(),
            "watcher": watcher.status(),
        }

    @app.get("/api/presets")
    def get_presets() -> dict:
        return {"presets": store.list_presets()}

    @app.post("/api/presets")
    def create_preset(payload: PresetCreatePayload) -> dict:
        try:
            opts = payload.to_options()
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

        preset = store.create_custom_preset(
            name=payload.name,
            description=payload.description,
            output_format=opts.output_format,
            quality=opts.quality,
            resize_mode=opts.resize_mode,
            max_megapixels=opts.max_megapixels,
            max_edge_px=opts.max_edge_px,
            strip_exif=opts.strip_exif,
            target_size_kb=opts.target_size_kb,
            normalize_srgb=opts.normalize_srgb,
            remove_background=opts.remove_background,
            aspect_ratio=opts.aspect_ratio,
            frame_mode=opts.frame_mode,
            watermark_text=opts.watermark_text,
            rename_pattern=opts.rename_pattern,
        )
        return {"preset": preset}

    @app.delete("/api/presets/{preset_id}")
    def remove_preset(preset_id: str) -> dict:
        if not store.delete_custom_preset(preset_id):
            raise HTTPException(status_code=404, detail="Custom preset not found.")
        return {"deleted": preset_id}

    @app.post("/api/convert")
    async def convert_uploads(
        files: Annotated[list[UploadFile], File(...)],
        output_format: Annotated[str, Form()] = "webp",
        quality: Annotated[int, Form()] = 85,
        target_size_kb: Annotated[int, Form()] = 0,
        resize_mode: Annotated[str, Form()] = "original",
        max_megapixels: Annotated[float, Form()] = 16.0,
        max_edge_px: Annotated[int, Form()] = 2048,
        strip_exif: Annotated[bool, Form()] = True,
        repair_filename_dates: Annotated[bool, Form()] = True,
        normalize_srgb: Annotated[bool, Form()] = True,
        remove_background: Annotated[bool, Form()] = False,
        aspect_ratio: Annotated[str, Form()] = "original",
        frame_mode: Annotated[str, Form()] = "blur",
        watermark_text: Annotated[str, Form()] = "",
        watermark_position: Annotated[str, Form()] = "bottom-right",
        watermark_opacity: Annotated[int, Form()] = 65,
        rename_pattern: Annotated[str, Form()] = "{orig}",
        preset_slug: Annotated[str, Form()] = "custom",
    ) -> dict:
        if not files:
            raise HTTPException(status_code=400, detail="Select at least one image.")
        try:
            opts = ConvertOptions(
                output_format=output_format,  # type: ignore[arg-type]
                quality=quality,
                target_size_kb=target_size_kb,
                resize_mode=resize_mode,  # type: ignore[arg-type]
                max_megapixels=max_megapixels,
                max_edge_px=max_edge_px,
                strip_exif=strip_exif,
                repair_filename_dates=repair_filename_dates,
                normalize_srgb=normalize_srgb,
                remove_background=remove_background,
                aspect_ratio=aspect_ratio,  # type: ignore[arg-type]
                frame_mode=frame_mode,  # type: ignore[arg-type]
                watermark_text=watermark_text,
                watermark_position=watermark_position,  # type: ignore[arg-type]
                watermark_opacity=watermark_opacity,
                rename_pattern=rename_pattern,
                preset_slug=preset_slug,
            ).normalized()
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

        results: list[dict] = []
        errors: list[dict] = []
        for idx, upload in enumerate(files, start=1):
            raw = await upload.read()
            fname = upload.filename or f"photo_{idx}.jpg"
            try:
                artifact = convert_image_bytes(raw, fname, opts, outputs_dir, seq=idx)
                results.append(store.record_conversion(artifact))
            except Exception as exc:
                errors.append({"filename": fname, "error": str(exc)})

        if not results and errors:
            raise HTTPException(status_code=400, detail=errors[0]["error"])

        return {
            "items": results,
            "errors": errors,
            "summary": store.summary(),
        }

    @app.post("/api/preview")
    async def preview_before_after(
        file: Annotated[UploadFile | None, File()] = None,
        sample_only: Annotated[bool, Form()] = False,
        output_format: Annotated[str, Form()] = "webp",
        quality: Annotated[int, Form()] = 85,
        target_size_kb: Annotated[int, Form()] = 0,
        resize_mode: Annotated[str, Form()] = "original",
        max_megapixels: Annotated[float, Form()] = 16.0,
        max_edge_px: Annotated[int, Form()] = 2048,
        strip_exif: Annotated[bool, Form()] = True,
        repair_filename_dates: Annotated[bool, Form()] = True,
        normalize_srgb: Annotated[bool, Form()] = True,
        remove_background: Annotated[bool, Form()] = False,
        aspect_ratio: Annotated[str, Form()] = "original",
        frame_mode: Annotated[str, Form()] = "blur",
        watermark_text: Annotated[str, Form()] = "",
        watermark_position: Annotated[str, Form()] = "bottom-right",
        watermark_opacity: Annotated[int, Form()] = 65,
    ) -> dict:
        if file is not None:
            raw = await file.read()
            fname = file.filename or "preview.jpg"
        elif sample_only:
            fname, raw = build_sample_photos()[0]
        else:
            raise HTTPException(status_code=400, detail="Provide a photo or set sample_only=true.")

        try:
            opts = ConvertOptions(
                output_format=output_format,  # type: ignore[arg-type]
                quality=quality,
                target_size_kb=target_size_kb,
                resize_mode=resize_mode,  # type: ignore[arg-type]
                max_megapixels=max_megapixels,
                max_edge_px=max_edge_px,
                strip_exif=strip_exif,
                repair_filename_dates=repair_filename_dates,
                normalize_srgb=normalize_srgb,
                remove_background=remove_background,
                aspect_ratio=aspect_ratio,  # type: ignore[arg-type]
                frame_mode=frame_mode,  # type: ignore[arg-type]
                watermark_text=watermark_text,
                watermark_position=watermark_position,  # type: ignore[arg-type]
                watermark_opacity=watermark_opacity,
            ).normalized()
            return generate_loupe_preview(raw, fname, opts)
        except Exception as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/convert/sample")
    def convert_samples(payload: SampleConvertPayload) -> dict:
        try:
            opts = payload.to_options()
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

        results: list[dict] = []
        for idx, (fname, raw) in enumerate(build_sample_photos(), start=1):
            artifact = convert_image_bytes(raw, fname, opts, outputs_dir, seq=idx)
            results.append(store.record_conversion(artifact))

        return {
            "items": results,
            "errors": [],
            "summary": store.summary(),
        }

    @app.post("/api/export-pdf")
    async def export_uploads_to_pdf(
        files: Annotated[list[UploadFile], File(...)],
        page_size: Annotated[str, Form()] = "a4",
        margin: Annotated[str, Form()] = "small",
        document_scan_filter: Annotated[bool, Form()] = False,
    ) -> Response:
        if not files:
            raise HTTPException(status_code=400, detail="Select at least one image for PDF export.")
        images: list[tuple[str, bytes]] = []
        for up in files:
            data = await up.read()
            if data:
                images.append((up.filename or "page.jpg", data))
        try:
            pdf_bytes = build_pdf_from_images(
                images,
                page_size=page_size,
                margin=margin,
                document_scan_filter=document_scan_filter,
            )
        except Exception as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

        return Response(
            content=pdf_bytes,
            media_type="application/pdf",
            headers={"Content-Disposition": 'attachment; filename="PhotoConvert_ScanSheet.pdf"'},
        )

    @app.post("/api/export-pdf/history")
    def export_history_to_pdf(payload: PdfHistoryPayload) -> Response:
        if not payload.ids:
            raise HTTPException(status_code=400, detail="Select at least one converted item.")
        images: list[tuple[str, bytes]] = []
        for item_id in payload.ids:
            rec = store.get_file_record(item_id)
            if not rec:
                continue
            fpath = Path(rec["output_path"])
            if fpath.is_file():
                images.append((rec["output_filename"], fpath.read_bytes()))
        if not images:
            raise HTTPException(status_code=404, detail="No valid images found for PDF export.")
        pdf_bytes = build_pdf_from_images(
            images,
            page_size=payload.page_size,
            margin=payload.margin,
            document_scan_filter=payload.document_scan_filter,
        )
        return Response(
            content=pdf_bytes,
            media_type="application/pdf",
            headers={"Content-Disposition": 'attachment; filename="PhotoConvert_Batch.pdf"'},
        )

    @app.get("/api/watcher")
    def get_watcher_status() -> dict:
        return watcher.status()

    @app.post("/api/watcher/config")
    def update_watcher_config(payload: WatcherConfigPayload) -> dict:
        try:
            opts = payload.to_options()
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return watcher.configure(enabled=payload.enabled, options=opts)

    @app.post("/api/watcher/drop-sample")
    def drop_watcher_sample() -> dict:
        dropped = watcher.drop_sample_to_inbox()
        converted = watcher.scan_once() if watcher.enabled else []
        return {
            "dropped_file": dropped,
            "auto_converted": converted,
            "watcher": watcher.status(),
            "summary": store.summary(),
        }

    @app.post("/api/watcher/scan")
    def scan_watcher_now() -> dict:
        converted = watcher.scan_once()
        return {
            "converted": converted,
            "watcher": watcher.status(),
            "summary": store.summary(),
        }

    @app.get("/api/history")
    def get_history(limit: int = Query(default=100, ge=1, le=500)) -> dict:
        return {
            "items": store.list_history(limit=limit),
            "summary": store.summary(),
        }

    @app.delete("/api/history")
    def clear_all_history() -> dict:
        removed = store.clear_history()
        return {"cleared": removed, "summary": store.summary()}

    @app.delete("/api/history/{item_id}")
    def delete_history_item(item_id: str) -> dict:
        if not store.delete_item(item_id):
            raise HTTPException(status_code=404, detail="Conversion record not found.")
        return {"deleted": item_id, "summary": store.summary()}

    @app.get("/api/files/{item_id}")
    def serve_converted_file(item_id: str, download: int = Query(default=0)) -> FileResponse:
        rec = store.get_file_record(item_id)
        if rec is None:
            raise HTTPException(status_code=404, detail="File not found.")
        path = Path(rec["output_path"])
        if not path.is_file():
            raise HTTPException(status_code=404, detail="Converted file missing on disk.")
        disposition = "attachment" if download else "inline"
        return FileResponse(
            path=path,
            media_type=rec["mime_type"],
            filename=rec["output_filename"] if download else None,
            headers={"Content-Disposition": f'{disposition}; filename="{rec["output_filename"]}"'},
        )

    @app.post("/api/download-zip")
    def download_batch_zip(payload: ZipDownloadPayload) -> StreamingResponse:
        if not payload.ids:
            raise HTTPException(status_code=400, detail="No conversion IDs supplied.")

        buf = io.BytesIO()
        used_names: dict[str, int] = {}
        added = 0
        with zipfile.ZipFile(buf, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
            for item_id in payload.ids:
                rec = store.get_file_record(item_id)
                if not rec:
                    continue
                fpath = Path(rec["output_path"])
                if not fpath.is_file():
                    continue
                name = rec["output_filename"]
                count = used_names.get(name, 0)
                used_names[name] = count + 1
                if count > 0:
                    stem = Path(name).stem
                    ext = Path(name).suffix
                    name = f"{stem}_{count + 1}{ext}"
                zf.write(fpath, arcname=name)
                added += 1

        if added == 0:
            raise HTTPException(status_code=404, detail="No valid converted files found.")

        buf.seek(0)
        return StreamingResponse(
            buf,
            media_type="application/zip",
            headers={"Content-Disposition": 'attachment; filename="PhotoConvert_Batch.zip"'},
        )

    if static_dir.is_dir():
        app.mount("/", StaticFiles(directory=str(static_dir), html=True), name="webui")

    return app


app = create_app()

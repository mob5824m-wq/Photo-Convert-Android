"""FastAPI application for Photo Convert Web Studio & Batch API."""

from __future__ import annotations

import io
import zipfile
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from server.converter import build_sample_photos, convert_image_bytes
from server.models import LOSSLESS_FORMATS, SUPPORTED_FORMATS, ConvertOptions
from server.store import ConvertStore
from server.versioning import read_current_version

REPO_ROOT = Path(__file__).resolve().parent.parent


class PresetCreatePayload(BaseModel):
    name: str
    description: str = ""
    output_format: str = "webp"
    quality: int = 85
    resize_mode: str = "original"
    max_megapixels: float = 16.0
    max_edge_px: int = 2048
    strip_exif: bool = True


class SampleConvertPayload(BaseModel):
    output_format: str = "webp"
    quality: int = 84
    resize_mode: str = "megapixels"
    max_megapixels: float = 16.0
    max_edge_px: int = 2048
    strip_exif: bool = True


class ZipDownloadPayload(BaseModel):
    ids: list[str]


def create_app(
    data_dir: Path | None = None,
    webui_dir: Path | None = None,
) -> FastAPI:
    runtime_dir = data_dir or (REPO_ROOT / "data")
    outputs_dir = runtime_dir / "outputs"
    outputs_dir.mkdir(parents=True, exist_ok=True)
    store = ConvertStore(runtime_dir / "photo_convert.db")
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
            "summary": store.summary(),
        }

    @app.get("/api/presets")
    def get_presets() -> dict:
        return {"presets": store.list_presets()}

    @app.post("/api/presets")
    def create_preset(payload: PresetCreatePayload) -> dict:
        try:
            opts = ConvertOptions(
                output_format=payload.output_format,  # type: ignore[arg-type]
                quality=payload.quality,
                resize_mode=payload.resize_mode,  # type: ignore[arg-type]
                max_megapixels=payload.max_megapixels,
                max_edge_px=payload.max_edge_px,
                strip_exif=payload.strip_exif,
            ).normalized()
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
        resize_mode: Annotated[str, Form()] = "original",
        max_megapixels: Annotated[float, Form()] = 16.0,
        max_edge_px: Annotated[int, Form()] = 2048,
        strip_exif: Annotated[bool, Form()] = True,
    ) -> dict:
        if not files:
            raise HTTPException(status_code=400, detail="Select at least one image.")
        try:
            opts = ConvertOptions(
                output_format=output_format,  # type: ignore[arg-type]
                quality=quality,
                resize_mode=resize_mode,  # type: ignore[arg-type]
                max_megapixels=max_megapixels,
                max_edge_px=max_edge_px,
                strip_exif=strip_exif,
            ).normalized()
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

        results: list[dict] = []
        errors: list[dict] = []
        for upload in files:
            raw = await upload.read()
            fname = upload.filename or "photo.jpg"
            try:
                artifact = convert_image_bytes(raw, fname, opts, outputs_dir)
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

    @app.post("/api/convert/sample")
    def convert_samples(payload: SampleConvertPayload) -> dict:
        try:
            opts = ConvertOptions(
                output_format=payload.output_format,  # type: ignore[arg-type]
                quality=payload.quality,
                resize_mode=payload.resize_mode,  # type: ignore[arg-type]
                max_megapixels=payload.max_megapixels,
                max_edge_px=payload.max_edge_px,
                strip_exif=payload.strip_exif,
            ).normalized()
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

        results: list[dict] = []
        for fname, raw in build_sample_photos():
            artifact = convert_image_bytes(raw, fname, opts, outputs_dir)
            results.append(store.record_conversion(artifact))

        return {
            "items": results,
            "errors": [],
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

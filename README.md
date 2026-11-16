# CloudSyncing

Legacy Android (Eclipse) project that connects to Dropbox and Google Drive.

This is an older sample kept for reference. It is not actively maintained.

## What it contains

- `DropboxFragment` and `google/GoogleFragment`: one screen per service
- Callback classes for folders, metadata, uploads and sync (`FolderCallback`, `LoadMetaDataCallback`,
  `UploadFileCallback`, `SyncFilesCallback`, `GoogleSyncCallback` and others)
- Bundled libraries: Dropbox core SDK 2.1.2, Google API client 1.22.0 with the Drive v3 service, OkHttp 3.6.0

## Notes

- Eclipse project layout (`src/`, `gen/`, `bin/`). Build output (`bin/`, `.class` files) is committed.
- No API keys are included. To try it you need your own Dropbox app and your own Google API project.
- This is old code. Expect to update the libraries and migrate the project before building it today.

Third-party libraries bundled as `.jar` files keep their own licences.

## License

MIT. See [LICENSE](LICENSE).

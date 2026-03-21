use std::sync::OnceLock;

pub type LogFn =
    unsafe extern "C" fn(level: u8, tag: *const u8, tag_len: usize, msg: *const u8, msg_len: usize);

static LOG_CB: OnceLock<LogFn> = OnceLock::new();

pub fn init(cb: LogFn) {
    let _ = LOG_CB.set(cb);
    log::set_logger(&KOTLIN_LOGGER).ok();
    log::set_max_level(log::LevelFilter::Debug);
}

static KOTLIN_LOGGER: KotlinLogger = KotlinLogger;

struct KotlinLogger;

impl log::Log for KotlinLogger {
    fn enabled(&self, _metadata: &log::Metadata) -> bool {
        if LOG_CB.get().is_none() {
            return false;
        }
        true
    }

    fn log(&self, record: &log::Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let Some(cb) = LOG_CB.get() else { return };
        let level: u8 = match record.level() {
            log::Level::Error => 4,
            log::Level::Warn => 3,
            log::Level::Info => 2,
            log::Level::Debug => 1,
            log::Level::Trace => 0,
        };
        let tag = record.target().as_bytes();
        let msg = format!("{}", record.args());
        let msg_bytes = msg.as_bytes();
        unsafe {
            cb(
                level,
                tag.as_ptr(),
                tag.len(),
                msg_bytes.as_ptr(),
                msg_bytes.len(),
            );
        }
    }

    fn flush(&self) {}
}

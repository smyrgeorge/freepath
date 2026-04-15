//! FreepathCodec — length-prefixed binary codec for request_response.
//! Must match the client codec in freepath-libp2p so the relay can participate
//! in the `/freepath/msg/1.0.0` request-response protocol.

use async_trait::async_trait;
use futures::{AsyncReadExt, AsyncWriteExt};
use libp2p::request_response;
use std::io;

pub const PROTOCOL_NAME: &str = "/freepath/msg/1.0.0";

#[derive(Clone)]
pub struct FreepathProtocol;

impl AsRef<str> for FreepathProtocol {
    fn as_ref(&self) -> &str {
        PROTOCOL_NAME
    }
}

#[derive(Clone, Default)]
pub struct FreepathCodec;

#[async_trait]
impl request_response::Codec for FreepathCodec {
    type Protocol = FreepathProtocol;
    type Request = Vec<u8>;
    type Response = Vec<u8>;

    async fn read_request<T>(&mut self, _: &FreepathProtocol, io: &mut T) -> io::Result<Vec<u8>>
    where
        T: futures::AsyncRead + Unpin + Send,
    {
        let mut len_buf = [0u8; 4];
        io.read_exact(&mut len_buf).await?;
        let len = u32::from_be_bytes(len_buf) as usize;
        let mut buf = vec![0u8; len];
        io.read_exact(&mut buf).await?;
        Ok(buf)
    }

    async fn read_response<T>(&mut self, _: &FreepathProtocol, io: &mut T) -> io::Result<Vec<u8>>
    where
        T: futures::AsyncRead + Unpin + Send,
    {
        let mut len_buf = [0u8; 4];
        io.read_exact(&mut len_buf).await?;
        let len = u32::from_be_bytes(len_buf) as usize;
        let mut buf = vec![0u8; len];
        io.read_exact(&mut buf).await?;
        Ok(buf)
    }

    async fn write_request<T>(
        &mut self,
        _: &FreepathProtocol,
        io: &mut T,
        req: Vec<u8>,
    ) -> io::Result<()>
    where
        T: futures::AsyncWrite + Unpin + Send,
    {
        let len = req.len() as u32;
        io.write_all(&len.to_be_bytes()).await?;
        io.write_all(&req).await?;
        io.close().await
    }

    async fn write_response<T>(
        &mut self,
        _: &FreepathProtocol,
        io: &mut T,
        res: Vec<u8>,
    ) -> io::Result<()>
    where
        T: futures::AsyncWrite + Unpin + Send,
    {
        let len = res.len() as u32;
        io.write_all(&len.to_be_bytes()).await?;
        io.write_all(&res).await?;
        io.close().await
    }
}

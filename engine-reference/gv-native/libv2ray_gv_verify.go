package libv2ray

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	corenet "github.com/xtls/xray-core/common/net"
	core "github.com/xtls/xray-core/core"
)

// VerifyConnection validates one real HTTPS response through the active core.
// Unlike the upstream latency benchmark, it never waits for a second sample.
// It keeps normal certificate validation and accepts only HTTP 200/204.
func (x *CoreController) VerifyConnection(url string) (int64, error) {
	if !strings.HasPrefix(url, "https://") {
		return -1, errors.New("verification requires HTTPS")
	}
	x.CancelVerification()
	ctx, cancel := context.WithTimeout(context.Background(), 4500*time.Millisecond)
	x.verifyMutex.Lock()
	x.verifyCancel = cancel
	x.verifyMutex.Unlock()
	defer cancel()
	x.coreMutex.Lock()
	inst := x.coreInstance
	x.coreMutex.Unlock()
	if inst == nil {
		return -1, errors.New("core instance is nil")
	}
	transport := &http.Transport{
		TLSHandshakeTimeout: 4 * time.Second,
		DisableKeepAlives:   true,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dest, err := corenet.ParseDestination(fmt.Sprintf("%s:%s", network, addr))
			if err != nil {
				return nil, err
			}
			return core.Dial(ctx, inst, dest)
		},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 4500 * time.Millisecond,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}
	request, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return -1, err
	}
	started := time.Now()
	response, err := client.Do(request)
	if err != nil {
		return -1, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusNoContent {
		return -1, fmt.Errorf("verification HTTP status %d", response.StatusCode)
	}
	if _, err := io.Copy(io.Discard, io.LimitReader(response.Body, 4096)); err != nil {
		return -1, err
	}
	return time.Since(started).Milliseconds(), nil
}

// CancelVerification cancels the native context as well as the Java wait.
func (x *CoreController) CancelVerification() {
	x.verifyMutex.Lock()
	cancel := x.verifyCancel
	x.verifyCancel = nil
	x.verifyMutex.Unlock()
	if cancel != nil {
		cancel()
	}
}

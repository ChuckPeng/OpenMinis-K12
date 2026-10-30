package deviceapi

import (
	"sync"
)

// Notifier broadcasts state changes to waiting long-poll clients.
type Notifier struct {
	mu   sync.Mutex
	subs map[string][]chan struct{}
}

// NewNotifier creates an active notifier instance.
func NewNotifier() *Notifier {
	return &Notifier{
		subs: make(map[string][]chan struct{}),
	}
}

// Sub registers a listener for changes matching key.
func (n *Notifier) Sub(key string) (<-chan struct{}, func()) {
	n.mu.Lock()
	defer n.mu.Unlock()
	ch := make(chan struct{}, 1)
	n.subs[key] = append(n.subs[key], ch)
	cancel := func() {
		n.mu.Lock()
		defer n.mu.Unlock()
		list := n.subs[key]
		for i, c := range list {
			if c == ch {
				n.subs[key] = append(list[:i], list[i+1:]...)
				break
			}
		}
		if len(n.subs[key]) == 0 {
			delete(n.subs, key)
		}
	}
	return ch, cancel
}

// Wake signals all listeners waiting on key.
func (n *Notifier) Wake(key string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, ch := range n.subs[key] {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}


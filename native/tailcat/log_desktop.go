//go:build !android

package main

import (
	"fmt"
	"os"
)

func platformLog(message string) {
	if os.Getenv("KINUVO_TAILCAT_DEBUG") == "1" {
		fmt.Fprintln(os.Stderr, message)
	}
}

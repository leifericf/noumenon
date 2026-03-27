package main

import (
	"fmt"
	"myapp/pkg"
)

func main() {
	conn := pkg.Connect()
	fmt.Println("Connected:", conn)
}

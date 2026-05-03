using System;
using MyApp.Models;
using MyApp.Services;

namespace MyApp
{
    class Program
    {
        static void Main(string[] args)
        {
            var user = new User("Alice");
            var service = new UserService();
            service.Greet(user);
        }
    }
}

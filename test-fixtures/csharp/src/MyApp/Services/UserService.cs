using System.Linq;
using MyApp.Models;

namespace MyApp.Services
{
    public class UserService
    {
        public void Greet(User user)
        {
            Console.WriteLine($"Hello, {user.Name}!");
        }
    }
}

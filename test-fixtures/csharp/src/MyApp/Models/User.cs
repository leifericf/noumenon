using MyApp.Services;

namespace MyApp.Models
{
    public class User
    {
        public string Name { get; }
        public User(string name) => Name = name;
    }
}

defmodule MyApp.Accounts do
  alias MyApp.Repo
  import Ecto.Query
  require Logger

  def list_users do
    Logger.info("listing users")
    Repo.all(from u in "users", select: u)
  end
end

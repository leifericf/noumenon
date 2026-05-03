defmodule MyApp do
  alias MyApp.{Accounts, Repo}

  def start do
    Accounts.list_users()
  end
end

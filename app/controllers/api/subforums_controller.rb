class Api::SubforumsController < Api::ApiController
  load_and_authorize_resource :subforum

  include SubscriptionActions
  has_subscribable :subforum

  def show
    @threads = @subforum.
      threads_for_user(current_user).
      includes(:created_by, :last_post_created_by).
      order(pinned: :desc, last_post_created_at: :desc)
    @autocomplete_users = User.select(:id, :first_name, :last_name).ordered_by_first_name
    @valid_broadcast_groups = Group.all + [Group::Subscribers.new("Subforum Subscribers")]
  end
end
